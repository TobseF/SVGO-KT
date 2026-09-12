package io.github.tobsef.svgo.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Command line tests against an in-memory [CliIo], so they run on every target -- the JVM and
 * native hosts only provide file access, the behaviour under test lives in `commonMain`.
 */
class CliTest {

    private class FakeIo(
        private val files: MutableMap<String, String> = LinkedHashMap(),
        private val directories: MutableSet<String> = LinkedHashSet(),
        private val stdin: String = "",
    ) : CliIo {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        fun put(path: String, text: String) {
            files[path] = text
            parentOf(path)?.let { directories.add(it) }
        }

        fun read(path: String): String? = files[path]

        override fun readText(path: String): String = files[path] ?: error("missing $path")

        override fun writeText(path: String, text: String) {
            files[path] = text
        }

        override fun readStdin(): String = stdin

        override fun isDirectory(path: String): Boolean = path in directories

        override fun isFile(path: String): Boolean = path in files

        override fun listSvgFiles(directory: String, recursive: Boolean): List<String> =
            files.keys.filter {
                it.startsWith("$directory/") && it.endsWith(".svg") &&
                    (recursive || '/' !in it.removePrefix("$directory/"))
            }.sorted()

        override fun createDirectories(path: String) {
            directories.add(path)
        }

        override fun out(text: String) {
            stdout.append(text)
        }

        override fun err(text: String) {
            stderr.append(text).append('\n')
        }

        override val defaultEol: String get() = "lf"
    }

    private val rect = """<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>"""
    private val optimizedRect = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0h10v10H0z"/></svg>"""

    @Test
    fun optimizesAStringToStdout() {
        val io = FakeIo()
        assertEquals(0, runCli(listOf("-s", rect, "-o", "-"), io))
        assertEquals(optimizedRect, io.stdout.toString())
    }

    @Test
    fun optimizesStdinToStdout() {
        val io = FakeIo(stdin = rect)
        assertEquals(0, runCli(listOf("-i", "-", "-o", "-"), io))
        assertEquals(optimizedRect, io.stdout.toString())
    }

    @Test
    fun overwritesTheInputByDefault() {
        val io = FakeIo()
        io.put("icon.svg", rect)
        assertEquals(0, runCli(listOf("icon.svg"), io))
        assertEquals(optimizedRect, io.read("icon.svg"))
    }

    @Test
    fun writesToANamedOutput() {
        val io = FakeIo()
        io.put("in/icon.svg", rect)
        assertEquals(0, runCli(listOf("in/icon.svg", "-o", "out/icon.svg", "-q"), io))
        assertEquals(rect, io.read("in/icon.svg"))
        assertEquals(optimizedRect, io.read("out/icon.svg"))
    }

    @Test
    fun optimizesAFolderIntoAnotherFolder() {
        val io = FakeIo()
        io.put("assets/a.svg", rect)
        io.put("assets/b.svg", rect)
        io.put("assets/nested/c.svg", rect)
        io.put("assets/notes.txt", "ignored")

        assertEquals(0, runCli(listOf("-f", "assets", "-o", "dist", "-r", "-q"), io))

        assertEquals(optimizedRect, io.read("dist/a.svg"))
        assertEquals(optimizedRect, io.read("dist/b.svg"))
        assertEquals(optimizedRect, io.read("dist/nested/c.svg"))
        // the sources are untouched
        assertEquals(rect, io.read("assets/a.svg"))
    }

    @Test
    fun folderModeHonoursExclude() {
        val io = FakeIo()
        io.put("assets/keep.svg", rect)
        io.put("assets/skip-me.svg", rect)

        assertEquals(0, runCli(listOf("-f", "assets", "--exclude", "^skip", "-q"), io))

        assertEquals(optimizedRect, io.read("assets/keep.svg"))
        assertEquals(rect, io.read("assets/skip-me.svg"))
    }

    @Test
    fun folderModeWithoutRecursionSkipsSubfolders() {
        val io = FakeIo()
        io.put("assets/a.svg", rect)
        io.put("assets/nested/c.svg", rect)

        assertEquals(0, runCli(listOf("-f", "assets", "-q"), io))

        assertEquals(optimizedRect, io.read("assets/a.svg"))
        assertEquals(rect, io.read("assets/nested/c.svg"))
    }

    @Test
    fun appliesFormattingAndPrecisionOptions() {
        val io = FakeIo()
        val svg =
            """<svg xmlns="http://www.w3.org/2000/svg"><g><path d="M1.23456 2.34567L3.45678 4.56789"/></g></svg>"""
        assertEquals(0, runCli(listOf("-s", svg, "-o", "-", "-p", "2", "--pretty", "--indent", "2"), io))
        assertEquals(
            "<svg xmlns=\"http://www.w3.org/2000/svg\">\n  <path d=\"m1.23 2.35 2.23 2.22\"/>\n</svg>\n",
            io.stdout.toString(),
        )
    }

    @Test
    fun emitsDataUri() {
        val io = FakeIo()
        assertEquals(0, runCli(listOf("-s", rect, "-o", "-", "--datauri", "base64"), io))
        assertTrue(io.stdout.startsWith("data:image/svg+xml;base64,"))
    }

    @Test
    fun togglesPluginsOnAndOff() {
        val io = FakeIo()
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><title>t</title></svg>"""
        assertEquals(0, runCli(listOf("-s", svg, "-o", "-", "--enable", "removeTitle"), io))
        assertTrue("<title>" !in io.stdout.toString(), io.stdout.toString())
    }

    @Test
    fun reportsUsageErrors() {
        fun failureOf(vararg args: String): String {
            val io = FakeIo()
            assertEquals(1, runCli(args.toList(), io), "expected failure for ${args.toList()}")
            return io.stderr.toString()
        }

        assertTrue("'--datauri'" in failureOf("-s", rect, "--datauri", "nope"))
        assertTrue("'--precision'" in failureOf("-s", rect, "-p", "x"))
        assertTrue("'--eol'" in failureOf("-s", rect, "--eol", "cr"))
        assertTrue("unknown option" in failureOf("--nope"))
        assertTrue("unknown plugin" in failureOf("-s", rect, "--enable", "nope"))
        assertTrue("not part of preset-default" in failureOf("-s", rect, "--disable", "removeTitle"))
        assertTrue("already part of preset-default" in failureOf("-s", rect, "--enable", "removeComments"))
        assertTrue("does not exist" in failureOf("missing.svg"))
    }

    @Test
    fun showsHelpVersionAndPlugins() {
        val help = FakeIo()
        assertEquals(0, runCli(listOf("--help"), help))
        assertTrue("Usage:" in help.stdout.toString())

        val version = FakeIo()
        assertEquals(0, runCli(listOf("--version"), version))
        assertEquals("svgo-kt 4.1.0\n", version.stdout.toString())

        val plugins = FakeIo()
        assertEquals(0, runCli(listOf("--show-plugins"), plugins))
        assertTrue("convertPathData" in plugins.stdout.toString())

        // no input at all prints usage and fails
        val empty = FakeIo()
        assertEquals(1, runCli(emptyList(), empty))
        assertTrue("Usage:" in empty.stdout.toString())
    }

    @Test
    fun supportsEqualsSyntaxAndPathHelpers() {
        val io = FakeIo()
        assertEquals(0, runCli(listOf("-s", rect, "--output=-", "--datauri=unenc"), io))
        assertTrue(io.stdout.startsWith("data:image/svg+xml,"))

        // separators are normalized when computing a path relative to a folder
        assertEquals("nested/c.svg", relativePath("assets\\nested\\c.svg", "assets"))
        assertEquals("c.svg", relativePath("other/c.svg", "assets"))
        assertEquals("a.svg", basename("dir/sub/a.svg"))
        assertEquals("dir/sub", parentOf("dir/sub/a.svg"))
    }
}
