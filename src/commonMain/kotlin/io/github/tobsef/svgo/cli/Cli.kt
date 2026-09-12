package io.github.tobsef.svgo.cli

import io.github.tobsef.svgo.ALL_PLUGIN_NAMES
import io.github.tobsef.svgo.Config
import io.github.tobsef.svgo.PRESET_DEFAULT_PLUGIN_NAMES
import io.github.tobsef.svgo.Params
import io.github.tobsef.svgo.PluginConfig
import io.github.tobsef.svgo.StringifyOptions
import io.github.tobsef.svgo.SvgoParserError
import io.github.tobsef.svgo.VERSION
import io.github.tobsef.svgo.getPlugin
import io.github.tobsef.svgo.optimize
import io.github.tobsef.svgo.warningHandler
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything the command line needs from its host platform.
 *
 * Keeping I/O behind this interface is what lets the JVM and the Kotlin/Native builds share the
 * entire command line implementation.
 */
public interface CliIo {
    public fun readText(path: String): String
    public fun writeText(path: String, text: String)
    public fun readStdin(): String

    /** `true` when [path] is an existing directory. */
    public fun isDirectory(path: String): Boolean

    /** `true` when [path] exists as a readable file. */
    public fun isFile(path: String): Boolean

    /** Absolute or relative paths of the `*.svg` files in [directory]. */
    public fun listSvgFiles(directory: String, recursive: Boolean): List<String>

    /** Create [path] and any missing parent directories. */
    public fun createDirectories(path: String)

    /** Write to stdout verbatim -- no newline is appended. */
    public fun out(text: String)

    /** Write a diagnostic line to stderr. */
    public fun err(text: String)

    /** `"lf"` or `"crlf"` -- the platform's native line ending. */
    public val defaultEol: String
}

private const val USAGE = """svgo-kt $VERSION -- optimize/minify SVG

Usage:
  svgo [INPUT...] [options]
  svgo -f <folder> [options]
  svgo -s "<svg .../>" [options]
  cat in.svg | svgo -i - -o -

Input:
  -i, --input <FILE...>     Input files, "-" for stdin (also accepted as positional arguments)
  -s, --string <STRING>     Input SVG data string
  -f, --folder <FOLDER>     Input folder, optimize and rewrite all *.svg files
  -r, --recursive           Use with --folder, recurse into subfolders
      --exclude <REGEX...>  Use with --folder, skip files whose name matches

Output:
  -o, --output <FILE...>    Output file or folder (default: overwrite the input), "-" for stdout
      --datauri <FORMAT>    Output as a data URI: base64, enc or unenc

Optimization:
  -p, --precision <INT>     Number of digits in the fractional part, overrides plugin params
      --multipass           Pass over SVGs multiple times to ensure all optimizations are applied
      --disable <PLUGIN...>  Disable a preset-default plugin
      --enable <PLUGIN...>   Enable a plugin that is not part of preset-default

Formatting:
      --pretty              Pretty-print the output
      --indent <INT>        Indent width when pretty printing (default 4)
      --eol <EOL>           Line break to use: lf or crlf (default: platform native)
      --final-newline       Ensure the output ends with a line break

Other:
  -q, --quiet               Only output error messages
      --show-plugins        Show available plugins and exit
  -v, --version             Show the version and exit
  -h, --help                Show this help and exit"""

private class Options {
    val input = ArrayList<String>()
    val output = ArrayList<String>()
    val exclude = ArrayList<String>()
    val disable = ArrayList<String>()
    val enable = ArrayList<String>()
    var string: String? = null
    var folder: String? = null
    var precision: Int? = null
    var datauri: String? = null
    var indent: Int? = null
    var eol: String? = null
    var multipass = false
    var pretty = false
    var finalNewline = false
    var recursive = false
    var quiet = false
    var showPlugins = false
    var version = false
    var help = false
}

private class CliError(message: String) : Exception(message)

/**
 * Run the command line.
 *
 * @return the process exit code.
 */
public fun runCli(args: List<String>, io: CliIo): Int {
    val options: Options
    try {
        options = parseArgs(args)
    } catch (error: CliError) {
        io.err("error: ${error.message}")
        return 1
    }

    if (options.help) {
        io.out(USAGE + "\n")
        return 0
    }
    if (options.version) {
        io.out("svgo-kt $VERSION\n")
        return 0
    }
    if (options.showPlugins) {
        io.out("Currently available plugins:\n")
        for (name in ALL_PLUGIN_NAMES) {
            val plugin = getPlugin(name) ?: continue
            io.out("  $name".padEnd(34) + plugin.description + "\n")
        }
        return 0
    }

    return try {
        execute(options, io)
    } catch (error: CliError) {
        io.err("error: ${error.message}")
        1
    } catch (error: SvgoParserError) {
        io.err("error: ${error.message}")
        1
    }
}

// ---------------------------------------------------------------------------
// argument parsing
// ---------------------------------------------------------------------------

private fun parseArgs(args: List<String>): Options {
    val options = Options()
    var index = 0

    /** Collect the values of a multi-value option until the next option-looking token. */
    fun collect(into: MutableList<String>, name: String) {
        var found = false
        while (index < args.size && !isOptionToken(args[index])) {
            into.add(args[index])
            index++
            found = true
        }
        if (!found) throw CliError("option '$name' requires a value")
    }

    fun single(name: String): String {
        if (index >= args.size || isOptionToken(args[index])) throw CliError("option '$name' requires a value")
        return args[index++]
    }

    while (index < args.size) {
        val arg = args[index]
        // support --option=value
        if (arg.startsWith("--") && '=' in arg) {
            val name = arg.substringBefore('=')
            val value = arg.substringAfter('=')
            index++
            applyValueOption(options, name, value)
            continue
        }
        index++
        when (arg) {
            "-i", "--input" -> collect(options.input, arg)
            "-o", "--output" -> collect(options.output, arg)
            "--exclude" -> collect(options.exclude, arg)
            "--disable" -> collect(options.disable, arg)
            "--enable" -> collect(options.enable, arg)
            "-s", "--string" -> options.string = single(arg)
            "-f", "--folder" -> options.folder = single(arg)
            "-p", "--precision" -> applyValueOption(options, arg, single(arg))
            "--datauri" -> applyValueOption(options, arg, single(arg))
            "--indent" -> applyValueOption(options, arg, single(arg))
            "--eol" -> applyValueOption(options, arg, single(arg))
            "--multipass" -> options.multipass = true
            "--pretty" -> options.pretty = true
            "--final-newline" -> options.finalNewline = true
            "-r", "--recursive" -> options.recursive = true
            "-q", "--quiet" -> options.quiet = true
            "--show-plugins" -> options.showPlugins = true
            "-v", "--version" -> options.version = true
            "-h", "--help" -> options.help = true
            "-" -> options.input.add("-")
            else -> {
                if (isOptionToken(arg)) throw CliError("unknown option '$arg'")
                options.input.add(arg)
            }
        }
    }
    return options
}

/** A token that looks like an option -- `-` on its own is the stdin placeholder, not an option. */
private fun isOptionToken(token: String): Boolean = token.length > 1 && token.startsWith("-")

private fun applyValueOption(options: Options, name: String, value: String) {
    when (name) {
        "-p", "--precision" -> options.precision = value.toIntOrNull()
            ?: throw CliError("option '--precision' argument must be an integer number")
        "--indent" -> options.indent = value.toIntOrNull()
            ?: throw CliError("option '--indent' argument must be an integer number")
        "--datauri" -> {
            if (value != "base64" && value != "enc" && value != "unenc") {
                throw CliError("option '--datauri' must have one of the following values: 'base64', 'enc' or 'unenc'")
            }
            options.datauri = value
        }
        "--eol" -> {
            if (value != "lf" && value != "crlf") {
                throw CliError("option '--eol' must have one of the following values: 'lf' or 'crlf'")
            }
            options.eol = value
        }
        "-s", "--string" -> options.string = value
        "-f", "--folder" -> options.folder = value
        "-i", "--input" -> options.input.add(value)
        "-o", "--output" -> options.output.add(value)
        "--exclude" -> options.exclude.add(value)
        "--disable" -> options.disable.add(value)
        "--enable" -> options.enable.add(value)
        else -> throw CliError("unknown option '$name'")
    }
}

// ---------------------------------------------------------------------------
// execution
// ---------------------------------------------------------------------------

private fun buildConfig(options: Options, io: CliIo, path: String?): Config {
    for (name in options.disable + options.enable) {
        if (getPlugin(name) == null) throw CliError("unknown plugin '$name'")
    }
    for (name in options.disable) {
        if (name !in PRESET_DEFAULT_PLUGIN_NAMES) {
            throw CliError("'$name' is not part of preset-default, so there is nothing to disable")
        }
    }
    for (name in options.enable) {
        if (name in PRESET_DEFAULT_PLUGIN_NAMES) {
            throw CliError("'$name' is already part of preset-default")
        }
    }

    val overrides = LinkedHashMap<String, Any?>()
    for (name in options.disable) overrides[name] = false
    val presetParams = if (overrides.isEmpty()) null else Params.of("overrides" to overrides)

    val plugins = ArrayList<PluginConfig>()
    plugins.add(PluginConfig("preset-default", presetParams))
    for (name in options.enable) plugins.add(PluginConfig(name))

    return Config(
        plugins = plugins,
        multipass = options.multipass,
        floatPrecision = options.precision,
        path = path,
        js2svg = StringifyOptions(
            pretty = options.pretty,
            indent = options.indent,
            eol = options.eol ?: io.defaultEol,
            finalNewline = options.finalNewline,
        ),
        datauri = options.datauri,
    )
}

private fun execute(options: Options, io: CliIo): Int {
    warningHandler = { message -> io.err(message) }

    val string = options.string
    val folder = options.folder

    return when {
        string != null -> {
            val result = optimize(string, buildConfig(options, io, null))
            emit(result.data, options.output.firstOrNull() ?: "-", io, options, string.length, "<string>")
            0
        }

        folder != null -> optimizeFolder(folder, options, io)

        options.input.isNotEmpty() -> optimizeFiles(options, io)

        else -> {
            io.out(USAGE + "\n")
            1
        }
    }
}

private fun optimizeFiles(options: Options, io: CliIo): Int {
    val outputs = options.output
    // a single output that is an existing directory receives every input file
    val outputIsDirectory = outputs.size == 1 && outputs[0] != "-" &&
        (io.isDirectory(outputs[0]) || endsWithSeparator(outputs[0]))

    var failures = 0
    for ((index, input) in options.input.withIndex()) {
        val source = if (input == "-") io.readStdin() else readInput(input, io)
        val target = when {
            input == "-" -> outputs.getOrNull(index) ?: "-"
            outputIsDirectory -> joinPath(outputs[0], basename(input))
            else -> outputs.getOrNull(index) ?: input
        }
        try {
            val result = optimize(source, buildConfig(options, io, if (input == "-") null else input))
            if (outputIsDirectory) io.createDirectories(outputs[0])
            emit(result.data, target, io, options, source.length, input)
        } catch (error: SvgoParserError) {
            io.err("error: ${error.message}")
            failures++
        }
    }
    return if (failures == 0) 0 else 1
}

private fun optimizeFolder(folder: String, options: Options, io: CliIo): Int {
    if (!io.isDirectory(folder)) throw CliError("folder '$folder' does not exist")
    val targetFolder = options.output.firstOrNull()?.takeIf { it != "-" } ?: folder

    val excludes = options.exclude.map { Regex(it) }
    val files = io.listSvgFiles(folder, options.recursive)
        .filter { file -> excludes.none { it.containsMatchIn(basename(file)) } }

    if (files.isEmpty()) {
        if (!options.quiet) io.err("No SVG files have been found in '$folder'.")
        return 0
    }

    if (targetFolder != folder) io.createDirectories(targetFolder)

    var failures = 0
    for (file in files) {
        val source = readInput(file, io)
        val target = if (targetFolder == folder) {
            file
        } else {
            joinPath(targetFolder, relativePath(file, folder))
        }
        try {
            val result = optimize(source, buildConfig(options, io, file))
            parentOf(target)?.let { io.createDirectories(it) }
            emit(result.data, target, io, options, source.length, file)
        } catch (error: SvgoParserError) {
            io.err("error: ${error.message}")
            failures++
        }
    }
    return if (failures == 0) 0 else 1
}

private fun readInput(path: String, io: CliIo): String {
    if (!io.isFile(path)) throw CliError("input file '$path' does not exist")
    return io.readText(path)
}

private fun emit(
    data: String,
    target: String,
    io: CliIo,
    options: Options,
    originalSize: Int,
    label: String,
) {
    if (target == "-") {
        io.out(data)
        return
    }
    io.writeText(target, data)
    if (!options.quiet) {
        io.err("$label -> $target  ${formatSize(originalSize)} -> ${formatSize(data.length)} " +
            "(${formatPercent(originalSize, data.length)})")
    }
}

// ---------------------------------------------------------------------------
// small formatting/path helpers (no platform APIs)
// ---------------------------------------------------------------------------

internal fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    else -> {
        val kib = (bytes * 10.0 / 1024).roundToInt() / 10.0
        val whole = kib.toInt()
        val tenths = ((abs(kib) * 10).roundToInt() % 10)
        "$whole.$tenths KiB"
    }
}

internal fun formatPercent(before: Int, after: Int): String {
    if (before == 0) return "0.0%"
    val percent = (after - before) * 100.0 / before
    val rounded = (abs(percent) * 10).roundToInt()
    val sign = if (percent > 0) "+" else "-"
    return "$sign${rounded / 10}.${rounded % 10}%"
}

internal fun basename(path: String): String {
    val index = path.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (index < 0) path else path.substring(index + 1)
}

internal fun parentOf(path: String): String? {
    val index = path.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (index <= 0) null else path.substring(0, index)
}

/**
 * [file] expressed relative to [directory].
 *
 * Separators are normalized first: a host may list files with `\` while the folder was given with
 * `/` on the command line. Falls back to the bare file name when [file] is not below [directory].
 */
internal fun relativePath(file: String, directory: String): String {
    val normalizedFile = file.replace('\\', '/')
    val normalizedDirectory = directory.replace('\\', '/').trimEnd('/')
    return if (normalizedDirectory.isNotEmpty() && normalizedFile.startsWith("$normalizedDirectory/")) {
        normalizedFile.substring(normalizedDirectory.length + 1)
    } else {
        basename(file)
    }
}

internal fun joinPath(directory: String, name: String): String =
    if (endsWithSeparator(directory)) directory + name else "$directory/$name"

private fun endsWithSeparator(path: String): Boolean = path.endsWith('/') || path.endsWith('\\')
