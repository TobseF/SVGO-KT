package io.github.tobsef.svgo.cli

import java.io.File
import kotlin.system.exitProcess

/** JVM host for the shared command line. */
public object JvmCliIo : CliIo {
    override fun readText(path: String): String = File(path).readText(Charsets.UTF_8)

    override fun writeText(path: String, text: String) {
        File(path).writeText(text, Charsets.UTF_8)
    }

    override fun readStdin(): String = System.`in`.readBytes().decodeToString()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun isFile(path: String): Boolean = File(path).isFile

    override fun listSvgFiles(directory: String, recursive: Boolean): List<String> {
        val root = File(directory)
        val sequence = if (recursive) root.walkTopDown() else root.listFiles()?.asSequence().orEmpty()
        return sequence
            .filter { it.isFile && it.name.endsWith(".svg", ignoreCase = true) }
            .map { it.path }
            .sorted()
            .toList()
    }

    override fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    override fun out(text: String) {
        println(text)
    }

    override fun err(text: String) {
        System.err.println(text)
    }

    override val defaultEol: String
        get() = if (System.lineSeparator() == "\r\n") "crlf" else "lf"
}

public fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), JvmCliIo))
}
