package io.github.tobsef.svgo.cli

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.opendir
import platform.posix.readdir

/**
 * Create a single directory; `mkdir` has a different signature on Windows than on POSIX, so this is
 * the one thing the native hosts cannot share.
 */
internal expect fun makeDirectory(path: String): Boolean

/**
 * Kotlin/Native host for the shared command line.
 *
 * Everything goes through `fopen`/`opendir` rather than `stat`/`access`, which keeps the same code
 * working on Windows, Linux and macOS without per-platform branches.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
public object NativeCliIo : CliIo {

    override fun readText(path: String): String {
        val file = fopen(path, "rb") ?: throw RuntimeException("cannot read '$path'")
        try {
            return readAll(file).decodeToString()
        } finally {
            fclose(file)
        }
    }

    override fun writeText(path: String, text: String) {
        val file = fopen(path, "wb") ?: throw RuntimeException("cannot write '$path'")
        try {
            val bytes = text.encodeToByteArray()
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                }
            }
        } finally {
            fclose(file)
        }
    }

    override fun readStdin(): String = buildString {
        while (true) {
            val line = readlnOrNull() ?: break
            append(line).append('\n')
        }
    }

    override fun isDirectory(path: String): Boolean {
        val directory = opendir(path) ?: return false
        closedir(directory)
        return true
    }

    override fun isFile(path: String): Boolean {
        if (isDirectory(path)) return false
        val file = fopen(path, "rb") ?: return false
        fclose(file)
        return true
    }

    override fun listSvgFiles(directory: String, recursive: Boolean): List<String> {
        val results = ArrayList<String>()
        collectSvgFiles(directory, recursive, results)
        results.sort()
        return results
    }

    private fun collectSvgFiles(directory: String, recursive: Boolean, into: MutableList<String>) {
        val handle = opendir(directory) ?: return
        try {
            while (true) {
                val entry = readdir(handle) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val path = joinPath(directory, name)
                when {
                    isDirectory(path) -> if (recursive) collectSvgFiles(path, true, into)
                    name.endsWith(".svg", ignoreCase = true) -> into.add(path)
                }
            }
        } finally {
            closedir(handle)
        }
    }

    override fun createDirectories(path: String) {
        if (path.isEmpty() || isDirectory(path)) return
        parentOf(path)?.let { createDirectories(it) }
        makeDirectory(path)
    }

    override fun out(text: String) {
        print(text)
    }

    override fun err(text: String) {
        printErrorLine(text)
    }

    override val defaultEol: String
        get() = if (Platform.osFamily == OsFamily.WINDOWS) "crlf" else "lf"

    private fun readAll(file: CPointer<FILE>): ByteArray {
        val bufferSize = 1 shl 16
        val chunks = ArrayList<ByteArray>()
        var total = 0
        memScoped {
            val buffer = allocArray<ByteVar>(bufferSize)
            while (true) {
                val read = fread(buffer, 1.convert(), bufferSize.convert(), file).toInt()
                if (read <= 0) break
                chunks.add(buffer.readBytes(read))
                total += read
            }
        }
        val result = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}

/** Kotlin/Native has no `System.err`; this writes a diagnostic line to stderr. */
@OptIn(ExperimentalForeignApi::class)
private fun printErrorLine(text: String) {
    val bytes = (text + "\n").encodeToByteArray()
    bytes.usePinned { pinned ->
        fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), platform.posix.stderr)
    }
}

public fun main(args: Array<String>) {
    val code = runCli(args.toList(), NativeCliIo)
    if (code != 0) kotlin.system.exitProcess(code)
}
