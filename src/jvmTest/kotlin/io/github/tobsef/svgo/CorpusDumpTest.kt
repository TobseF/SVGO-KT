package io.github.tobsef.svgo

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Cross-validation helper (disabled by default): dumps the full `preset-default` output for every
 * fixture SVG so it can be diffed against the reference implementation.
 *
 * Enable with `-Dsvgo.corpusDump=<dir>`.
 */
class CorpusDumpTest {

    @Test
    fun dumpCorpus() {
        val outDir = System.getProperty("svgo.corpusDump") ?: return
        val target = File(outDir).apply { mkdirs() }
        val fixtures = File(javaClass.classLoader.getResource("fixtures")!!.toURI())

        for (file in fixtures.listFiles()!!.sortedBy { it.name }) {
            val data = file.readText().trim().replace("\r\n", "\n")
            val items = data.split(Regex("""\s*===\s*"""))
            val test = if (items.size == 2) items[1] else items[0]
            val original = test.split(Regex("""\s*@@@\s*"""))[0]
            val result = try {
                optimize(original, Config(path = file.path, multipass = true)).data
            } catch (error: Exception) {
                "ERROR: ${error::class.simpleName}: ${error.message}"
            }
            File(target, file.name.removeSuffix(".svg.txt") + ".out").writeText(result, Charsets.UTF_8)
        }
    }
}
