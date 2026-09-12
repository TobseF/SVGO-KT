package io.github.tobsef.svgo

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests over complete, real-world SVG documents.
 *
 * Each `<name>.svg` in `resources/realworld` is optimized with the default preset and compared to
 * `<name>.expected.svg`, which was produced by the reference JavaScript SVGO 4.1.0. Unlike the
 * per-plugin fixtures these exercise the whole pipeline, including plugin interaction.
 */
class RealWorldTest {

    private val dir: File = File(javaClass.classLoader.getResource("realworld")!!.toURI())

    @TestFactory
    fun realWorldSvgs(): List<DynamicTest> {
        val inputs = dir.listFiles { file -> file.name.endsWith(".svg") && !file.name.endsWith(".expected.svg") }
            ?.sortedBy { it.name }
            .orEmpty()
        check(inputs.isNotEmpty()) { "no real-world SVGs found in $dir" }

        return inputs.map { input ->
            DynamicTest.dynamicTest(input.name) {
                val expected = File(dir, input.name.removeSuffix(".svg") + ".expected.svg")
                val result = optimize(input.readText(), Config(path = input.path, multipass = true))
                assertTrue(result.data.length <= input.readText().length, "output grew for ${input.name}")
                assertEquals(expected.readText().trim().replace("\r\n", "\n"), result.data)
            }
        }
    }
}
