import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    kotlin("multiplatform") version "2.1.0"
    `maven-publish`
    signing
}

group = "io.github.tobsef"
version = "4.1.0"

repositories {
    mavenCentral()
}

/** Entry point of the command line, shared by every executable target. */
val cliEntryPoint = "io.github.tobsef.svgo.cli.main"

/**
 * Kotlin/Native cross-compiles only within the host's platform family, so a single machine cannot
 * produce every artifact. By default only the targets this host can actually build are declared,
 * which keeps the published Gradle metadata honest: a consumer is never told an iOS variant exists
 * when no `svgo-kt-iosarm64` module was published next to it.
 *
 * Pass `-PhostTargetsOnly=false` for a real multi-host release, where each OS publishes its own
 * artifacts into the same repository.
 */
val hostTargetsOnly: Boolean = (findProperty("hostTargetsOnly") as String?)?.toBoolean() ?: true
val hostManager = HostManager()

fun buildableHere(target: KonanTarget): Boolean =
    !hostTargetsOnly || hostManager.isEnabled(target)

kotlin {
    explicitApi()

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            maxHeapSize = "2g"
            systemProperty("svgo.corpusDump", System.getProperty("svgo.corpusDump") ?: "")
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
            }
        }
    }

    js(IR) {
        nodejs()
        browser()
    }

    // Kotlin/Wasm compiled to the JS/browser platform. Like `js`, the library is pure Kotlin, so it
    // builds straight from commonMain and publishes from any host next to the other artifacts.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        browser()
    }

    // Native targets -- the library is pure Kotlin, so every target shares commonMain verbatim.
    // The desktop ones additionally build a standalone `svgo` executable that needs no JVM.
    if (buildableHere(KonanTarget.MINGW_X64)) mingwX64 { binaries { executable("svgo") { entryPoint = cliEntryPoint } } }
    if (buildableHere(KonanTarget.LINUX_X64)) linuxX64 { binaries { executable("svgo") { entryPoint = cliEntryPoint } } }
    if (buildableHere(KonanTarget.MACOS_X64)) macosX64 { binaries { executable("svgo") { entryPoint = cliEntryPoint } } }
    if (buildableHere(KonanTarget.MACOS_ARM64)) macosArm64 { binaries { executable("svgo") { entryPoint = cliEntryPoint } } }
    if (buildableHere(KonanTarget.IOS_ARM64)) iosArm64()
    if (buildableHere(KonanTarget.IOS_SIMULATOR_ARM64)) iosSimulatorArm64()

    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

/**
 * A self-contained `svgo-kt-cli.jar` (library + CLI + kotlin-stdlib) runnable with `java -jar`.
 * The library itself has no dependencies, so "fat" here means little more than the stdlib.
 */
val jvmCliJar = tasks.register<Jar>("jvmCliJar") {
    group = "build"
    description = "Builds a runnable JVM command line jar."
    archiveBaseName.set("svgo-kt-cli")
    manifest { attributes("Main-Class" to "io.github.tobsef.svgo.cli.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    from(jvmCompilation.output.allOutputs)
    dependsOn(jvmCompilation.compileTaskProvider)
    from({
        jvmCompilation.runtimeDependencyFiles.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}

tasks.named("build") { dependsOn(jvmCliJar) }

// ---------------------------------------------------------------------------
// publishing
// ---------------------------------------------------------------------------

/**
 * With `-PhostTargetsOnly=false` the unbuildable targets are still declared (so the metadata
 * describes the full release); their publication tasks are skipped on this host.
 */
val hostUnsupportedPublications: List<String> = kotlin.targets
    .withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
    .filterNot { hostManager.isEnabled(it.konanTarget) }
    .map { it.name }

/** An empty javadoc jar; Maven Central requires one, and it keeps local POMs consistent. */
val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }

    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("svgo-kt")
            description.set(
                "A dependency-free Kotlin Multiplatform reimplementation of SVGO, the SVG optimizer.",
            )
            url.set("https://github.com/TobseF/svgo-kt")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("TobseF")
                    name.set("Tobse")
                }
            }
            scm {
                url.set("https://github.com/TobseF/svgo-kt")
                connection.set("scm:git:https://github.com/TobseF/svgo-kt.git")
            }
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY") ?: (findProperty("signing.key") as String?)
    val signingPassword = System.getenv("SIGNING_PASSWORD") ?: (findProperty("signing.password") as String?)
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    val isReleaseVersion = !version.toString().endsWith("SNAPSHOT")
    isRequired = isReleaseVersion && (!signingKey.isNullOrBlank() || project.hasProperty("signing.keyId"))
    sign(publishing.publications)
}

/**
 * Every publication reuses the single [javadocJar], so its signature (`*-javadoc.jar.asc`) is
 * produced by one `Sign` task yet consumed by all publish tasks. Gradle 9 rejects this implicit
 * cross-task dependency, so make each publish task explicitly depend on every signing task.
 */
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}

tasks.register<Zip>("zipCentralBundle") {
    group = "publishing"
    description = "Packs the local staging repository into a bundle for Maven Central Portal."
    dependsOn("publishAllPublicationsToStagingRepository")
    archiveFileName.set("bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("bundle"))
    from(layout.buildDirectory.dir("staging-repo"))
}

// Disable the publication tasks of targets this host cannot build.
hostUnsupportedPublications.forEach { publicationName ->
    val prefix = "publish" + publicationName.replaceFirstChar { it.uppercase() } + "Publication"
    tasks.matching { it.name.startsWith(prefix) }.configureEach { enabled = false }
}

tasks.register("reportPublishedTargets") {
    group = "publishing"
    description = "Lists the targets this host can publish."
    val declared = kotlin.targets.map { it.name }.sorted()
    val skipped = hostUnsupportedPublications.sorted()
    doLast {
        logger.lifecycle("Declared targets:  ${declared.joinToString(", ")}")
        logger.lifecycle("Published here:    ${(declared - skipped.toSet()).joinToString(", ")}")
        if (skipped.isNotEmpty()) {
            logger.lifecycle("Skipped (host):    ${skipped.joinToString(", ")}")
        }
        if (hostTargetsOnly) {
            logger.lifecycle(
                "hostTargetsOnly=true, so targets this host cannot build are not declared at all " +
                    "and the metadata only advertises what was published.",
            )
        }
    }
}
