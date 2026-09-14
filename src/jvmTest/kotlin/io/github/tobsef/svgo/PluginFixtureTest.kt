package io.github.tobsef.svgo

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals

/**
 * Fixture-driven plugin tests.
 *
 * Mirrors upstream `test/plugins/_index.test.js`: each `<name>.<NN>.svg.txt` fixture holds
 * `original @@@ expected @@@ params` (optionally preceded by a `description ===` block). The plugin
 * is run through [optimize] with `js2svg = { pretty: true }` and its output compared to `expected`.
 * As upstream does, the plugin is applied twice to also assert idempotence.
 */
class PluginFixtureTest {

    private val fixturesDir: File = resolveFixturesDir()

    private val filenamePattern = Regex("""^(.*)\.(\d+)\.svg\.txt$""")

    private val idempotenceExclude = setOf("addAttributesToSVGElement", "convertTransform")

    /**
     * Fixtures that are expected to fail.
     *
     * Empty: every fixture matches the reference SVGO output byte for byte. Do not add entries
     * here to silence a regression -- a listed fixture that starts passing fails the suite too, so
     * the list cannot quietly go stale.
     */
    private val knownGaps = emptyMap<String, String>()

    @TestFactory
    fun pluginFixtures(): List<DynamicTest> {
        val files = fixturesDir.listFiles()?.sortedBy { it.name }.orEmpty()
        check(files.isNotEmpty()) { "no fixtures found in $fixturesDir" }

        return files.mapNotNull { file ->
            val match = filenamePattern.matchEntire(file.name) ?: return@mapNotNull null
            val name = match.groupValues[1]
            val index = match.groupValues[2]
            val caseId = "$name.$index"
            if (getPlugin(name) == null) return@mapNotNull null

            DynamicTest.dynamicTest(caseId) {
                val gap = knownGaps[caseId]
                var failed = false
                try {
                    runFixture(name, file)
                } catch (error: AssertionError) {
                    if (gap == null) throw error
                    failed = true
                    println("known gap [$caseId]: $gap")
                }
                if (gap != null && !failed) {
                    throw AssertionError("$caseId is listed as a known gap but now passes; remove it")
                }
            }
        }
    }

    private fun runFixture(name: String, file: File) {
        val data = normalize(file.readText())
        val items = data.split(Regex("""\s*===\s*"""))
        val test = if (items.size == 2) items[1] else items[0]
        val parts = test.split(Regex("""\s*@@@\s*"""))
        val original = parts[0]
        val expected = parts[1]
        val params = if (parts.size > 2 && parts[2].isNotBlank()) Json.parseObject(parts[2]) else emptyMap()

        val plugin = PluginConfig(name, Params(params))
        val passes = if (name in idempotenceExclude) 1 else 2
        var last = original
        repeat(passes) {
            val result = optimize(
                last,
                Config(
                    path = file.path,
                    plugins = listOf(plugin),
                    js2svg = StringifyOptions(pretty = true),
                ),
            )
            last = result.data
            assertEquals(expected, normalize(result.data))
        }
    }

    private fun normalize(text: String): String = text.trim().replace("\r\n", "\n")

    private fun resolveFixturesDir(): File {
        val fromResources = javaClass.classLoader.getResource("fixtures")
        if (fromResources != null) return File(fromResources.toURI())
        return File("src/jvmTest/resources/fixtures")
    }
}
