package io.github.tobsef.svgo

/** Plugin engine + preset machinery. Port of `lib/svgo/plugins.js`. */

/** Contextual information handed to every plugin. */
public class PluginInfo(
    public var path: String? = null,
    public var multipassCount: Int = 0,
)

public typealias PluginFn = (root: Root, params: Params, info: PluginInfo) -> Visitor?

/** A resolved plugin: its name, its configured parameters and its implementation. */
public class Plugin(
    public val name: String,
    public val params: Params? = null,
    public val description: String = "",
    public val isPreset: Boolean = false,
    public val plugins: List<Plugin> = emptyList(),
    public val fn: PluginFn,
)

public fun invokePlugins(
    ast: Root,
    info: PluginInfo,
    plugins: List<Plugin>,
    overrides: Map<String, Any?>?,
    globalOverrides: Map<String, Any?>,
) {
    for (plugin in plugins) {
        val override = if (overrides != null && overrides.containsKey(plugin.name)) overrides[plugin.name] else null
        if (override == false) continue
        var params = mergeParams(plugin.params, globalOverrides)
        @Suppress("UNCHECKED_CAST")
        if (override is Map<*, *>) params = mergeParams(params, override as Map<String, Any?>)

        val visitor = plugin.fn(ast, params, info)
        if (visitor != null) visit(ast, visitor)
    }
}

public fun createPreset(name: String, plugins: List<Plugin>): Plugin {
    val pluginNames = plugins.map { it.name }
    return Plugin(
        name = name,
        isPreset = true,
        plugins = plugins,
        fn = { ast, params, info ->
            val floatPrecision = params.intOrNull("floatPrecision")
            val overrides = params.obj("overrides")
            val globalOverrides = LinkedHashMap<String, Any?>()
            if (floatPrecision != null) globalOverrides["floatPrecision"] = floatPrecision
            if (overrides != null) {
                for (pluginName in overrides.keys) {
                    if (pluginName !in pluginNames) {
                        warn("You are trying to configure $pluginName which is not part of $name.")
                    }
                }
            }
            invokePlugins(ast, info, plugins, overrides, globalOverrides)
            null
        },
    )
}

/** Diagnostics sink; assigned by the CLI (or by tests) to capture warnings. */
public var warningHandler: ((String) -> Unit)? = null

internal fun warn(message: String) {
    warningHandler?.invoke(message)
}
