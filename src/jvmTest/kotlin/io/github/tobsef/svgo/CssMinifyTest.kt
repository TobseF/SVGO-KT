package io.github.tobsef.svgo

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals

/**
 * Differential tests against `csso`, the minifier the reference SVGO uses.
 *
 * Each record in the `resources/css` corpora is a `@@@SHEET`/`@@@BLOCK` input, its `@@@EXPECT`ed
 * minification as produced by `csso` itself, terminated by `@@@END`. Stylesheet records go through
 * [minifyCss] and block records through [minifyBlock] -- the two entry points `minifyStyles` uses
 * for `<style>` elements and `style` attributes.
 */
class CssMinifyTest {

    private val file: File = File(javaClass.classLoader.getResource("css/minify.txt")!!.toURI())
    private val fuzzFile: File = File(javaClass.classLoader.getResource("css/fuzz.txt")!!.toURI())

    /** Hand-written cases, one per behaviour of csso's clean, replace and restructure stages. */
    @TestFactory
    fun cssoParity(): List<DynamicTest> = parity(file, "")

    /**
     * The same comparison over randomly generated stylesheets.
     *
     * Hand-written cases only cover the paths someone thought of; these are 1200 pseudo-random
     * sheets (fixed seed) built from the selector, property and value vocabulary the restructuring
     * passes actually branch on, run through the reference `csso` once and recorded.
     */
    @TestFactory
    fun cssoParityFuzz(): List<DynamicTest> = parity(fuzzFile, "fuzz ")

    private fun parity(source: File, prefix: String): List<DynamicTest> {
        val records = source.readText().replace("\r\n", "\n").split("@@@END\n").filter { it.isNotBlank() }
        check(records.isNotEmpty()) { "no records in $source" }

        return records.mapIndexed { index, record ->
            val isBlock = record.startsWith("@@@BLOCK")
            val body = record.removePrefix("@@@SHEET\n").removePrefix("@@@BLOCK\n")
            val parts = body.split("@@@EXPECT\n")
            val input = parts[0].removeSuffix("\n")
            val expected = parts[1].removeSuffix("\n")

            DynamicTest.dynamicTest("$prefix${index + 1}: ${input.take(60)}") {
                val actual = if (isBlock) minifyBlock(input) else minifyCss(input)
                assertEquals(expected, actual, "input: $input")
            }
        }
    }
}
