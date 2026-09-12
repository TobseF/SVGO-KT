package io.github.tobsef.svgo

import io.github.tobsef.svgo.plugins.addAttributesToSVGElement
import io.github.tobsef.svgo.plugins.addClassesToSVGElement
import io.github.tobsef.svgo.plugins.cleanupAttrs
import io.github.tobsef.svgo.plugins.cleanupEnableBackground
import io.github.tobsef.svgo.plugins.cleanupIds
import io.github.tobsef.svgo.plugins.cleanupListOfValues
import io.github.tobsef.svgo.plugins.cleanupNumericValues
import io.github.tobsef.svgo.plugins.collapseGroups
import io.github.tobsef.svgo.plugins.convertColors
import io.github.tobsef.svgo.plugins.convertEllipseToCircle
import io.github.tobsef.svgo.plugins.convertOneStopGradients
import io.github.tobsef.svgo.plugins.convertPathData
import io.github.tobsef.svgo.plugins.convertShapeToPath
import io.github.tobsef.svgo.plugins.convertStyleToAttrs
import io.github.tobsef.svgo.plugins.convertTransform
import io.github.tobsef.svgo.plugins.inlineStyles
import io.github.tobsef.svgo.plugins.mergePaths
import io.github.tobsef.svgo.plugins.mergeStyles
import io.github.tobsef.svgo.plugins.minifyStyles
import io.github.tobsef.svgo.plugins.moveElemsAttrsToGroup
import io.github.tobsef.svgo.plugins.moveGroupAttrsToElems
import io.github.tobsef.svgo.plugins.prefixIds
import io.github.tobsef.svgo.plugins.removeAttributesBySelector
import io.github.tobsef.svgo.plugins.removeAttrs
import io.github.tobsef.svgo.plugins.removeComments
import io.github.tobsef.svgo.plugins.removeDeprecatedAttrs
import io.github.tobsef.svgo.plugins.removeDesc
import io.github.tobsef.svgo.plugins.removeDimensions
import io.github.tobsef.svgo.plugins.removeDoctype
import io.github.tobsef.svgo.plugins.removeEditorsNSData
import io.github.tobsef.svgo.plugins.removeElementsByAttr
import io.github.tobsef.svgo.plugins.removeEmptyAttrs
import io.github.tobsef.svgo.plugins.removeEmptyContainers
import io.github.tobsef.svgo.plugins.removeEmptyText
import io.github.tobsef.svgo.plugins.removeHiddenElems
import io.github.tobsef.svgo.plugins.removeMetadata
import io.github.tobsef.svgo.plugins.removeNonInheritableGroupAttrs
import io.github.tobsef.svgo.plugins.removeOffCanvasPaths
import io.github.tobsef.svgo.plugins.removeRasterImages
import io.github.tobsef.svgo.plugins.removeScripts
import io.github.tobsef.svgo.plugins.removeStyleElement
import io.github.tobsef.svgo.plugins.removeTitle
import io.github.tobsef.svgo.plugins.removeUnknownsAndDefaults
import io.github.tobsef.svgo.plugins.removeUnusedNS
import io.github.tobsef.svgo.plugins.removeUselessDefs
import io.github.tobsef.svgo.plugins.removeUselessStrokeAndFill
import io.github.tobsef.svgo.plugins.removeViewBox
import io.github.tobsef.svgo.plugins.removeXMLNS
import io.github.tobsef.svgo.plugins.removeXMLProcInst
import io.github.tobsef.svgo.plugins.removeXlink
import io.github.tobsef.svgo.plugins.reusePaths
import io.github.tobsef.svgo.plugins.sortAttrs
import io.github.tobsef.svgo.plugins.sortDefsChildren

/** Built-in plugin registry. Port of `lib/builtin.js`. */
public class PluginDefinition(
    public val name: String,
    public val description: String,
    public val fn: PluginFn,
)

/** Plugins that make up `preset-default`, in execution order. */
public val PRESET_DEFAULT_PLUGIN_NAMES: List<String> = listOf(
    "removeDoctype",
    "removeXMLProcInst",
    "removeComments",
    "removeDeprecatedAttrs",
    "removeMetadata",
    "removeEditorsNSData",
    "cleanupAttrs",
    "mergeStyles",
    "inlineStyles",
    "minifyStyles",
    "cleanupIds",
    "removeUselessDefs",
    "cleanupNumericValues",
    "convertColors",
    "removeUnknownsAndDefaults",
    "removeNonInheritableGroupAttrs",
    "removeUselessStrokeAndFill",
    "cleanupEnableBackground",
    "removeHiddenElems",
    "removeEmptyText",
    "convertShapeToPath",
    "convertEllipseToCircle",
    "moveElemsAttrsToGroup",
    "moveGroupAttrsToElems",
    "collapseGroups",
    "convertPathData",
    "convertTransform",
    "removeEmptyAttrs",
    "removeEmptyContainers",
    "mergePaths",
    "removeUnusedNS",
    "sortAttrs",
    "sortDefsChildren",
    "removeDesc",
)

private val REGISTRY: Map<String, PluginDefinition> = listOf(
    addAttributesToSVGElement,
    addClassesToSVGElement,
    cleanupAttrs,
    cleanupEnableBackground,
    cleanupIds,
    cleanupListOfValues,
    cleanupNumericValues,
    collapseGroups,
    convertColors,
    convertEllipseToCircle,
    convertOneStopGradients,
    convertPathData,
    convertShapeToPath,
    convertStyleToAttrs,
    convertTransform,
    inlineStyles,
    mergePaths,
    mergeStyles,
    minifyStyles,
    moveElemsAttrsToGroup,
    moveGroupAttrsToElems,
    prefixIds,
    removeAttributesBySelector,
    removeAttrs,
    removeComments,
    removeDeprecatedAttrs,
    removeDesc,
    removeDimensions,
    removeDoctype,
    removeEditorsNSData,
    removeElementsByAttr,
    removeEmptyAttrs,
    removeEmptyContainers,
    removeEmptyText,
    removeHiddenElems,
    removeMetadata,
    removeNonInheritableGroupAttrs,
    removeOffCanvasPaths,
    removeRasterImages,
    removeScripts,
    removeStyleElement,
    removeTitle,
    removeUnknownsAndDefaults,
    removeUnusedNS,
    removeUselessDefs,
    removeUselessStrokeAndFill,
    removeViewBox,
    removeXMLNS,
    removeXMLProcInst,
    removeXlink,
    reusePaths,
    sortAttrs,
    sortDefsChildren,
).associateBy { it.name }

private val ALIASES = mapOf(
    // removeScriptElement was renamed to removeScripts
    "removeScriptElement" to "removeScripts",
)

/** Names of every built-in plugin, sorted alphabetically. */
public val ALL_PLUGIN_NAMES: List<String> = REGISTRY.keys.sorted()

private val presetDefault: Plugin by lazy {
    createPreset(
        "preset-default",
        PRESET_DEFAULT_PLUGIN_NAMES.map { name ->
            val definition = REGISTRY[name] ?: error("preset-default plugin not implemented: $name")
            Plugin(name = definition.name, params = Params.EMPTY, description = definition.description, fn = definition.fn)
        },
    )
}

/** Resolve a built-in plugin (or the `preset-default` preset) by name. */
public fun getPlugin(name: String): Plugin? {
    if (name == "preset-default") return presetDefault
    val resolved = ALIASES[name] ?: name
    val definition = REGISTRY[resolved] ?: return null
    return Plugin(name = definition.name, description = definition.description, fn = definition.fn)
}

/** Every built-in plugin, plus the `preset-default` preset -- mirrors SVGO's `builtinPlugins`. */
public fun builtinPlugins(): List<Plugin> =
    listOf(presetDefault) + ALL_PLUGIN_NAMES.mapNotNull { getPlugin(it) }
