package io.github.tobsef.svgo

/** Public `optimize` entry point. Port of `lib/svgo.js`. */

public const val VERSION: String = "4.1.0"

/** One entry of the `plugins` config list: a built-in name, custom parameters or a custom `fn`. */
public class PluginConfig(
    public val name: String,
    public val params: Params? = null,
    public val fn: PluginFn? = null,
)

/** Optimizer configuration, mirroring SVGO's JS config object. */
public class Config(
    /** `null` falls back to `preset-default`; an empty list runs no plugins at all. */
    public val plugins: List<PluginConfig>? = null,
    public val multipass: Boolean = false,
    public val floatPrecision: Int? = null,
    public val path: String? = null,
    public val js2svg: StringifyOptions? = null,
    /** `"base64"`, `"enc"` or `"unenc"` to wrap the output in a data URI. */
    public val datauri: String? = null,
)

public class OptimizeResult(public val data: String)

private fun resolvePluginConfig(plugin: PluginConfig): Plugin {
    val fn = plugin.fn ?: getPlugin(plugin.name)?.fn
        ?: throw IllegalArgumentException("Unknown builtin plugin \"${plugin.name}\" specified.")
    return Plugin(name = plugin.name, params = plugin.params, fn = fn)
}

public fun optimize(input: String, config: Config = Config()): OptimizeResult {
    var source = input
    val maxPassCount = if (config.multipass) 10 else 1
    var prevResultSize = Int.MAX_VALUE
    var output = ""
    val info = PluginInfo(path = config.path)

    for (i in 0 until maxPassCount) {
        info.multipassCount = i
        val ast = parseSvg(source, config.path)
        // NOTE: JS uses `config.plugins || [...]`; an empty array is truthy in JS, so it must be
        // preserved (do not fall back to preset-default).
        val plugins = config.plugins ?: listOf(PluginConfig("preset-default"))
        val resolved = plugins.map { resolvePluginConfig(it) }

        val globalOverrides = LinkedHashMap<String, Any?>()
        if (config.floatPrecision != null) globalOverrides["floatPrecision"] = config.floatPrecision

        invokePlugins(ast, info, resolved, null, globalOverrides)
        output = stringifySvg(ast, config.js2svg)
        if (output.length < prevResultSize) {
            source = output
            prevResultSize = output.length
        } else {
            break
        }
    }

    if (config.datauri != null) output = encodeSvgDatauri(output, config.datauri)
    return OptimizeResult(output)
}
