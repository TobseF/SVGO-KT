package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.Transform
import io.github.tobsef.svgo.TransformParams
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.js2transform
import io.github.tobsef.svgo.jsNumberToString
import io.github.tobsef.svgo.matrixToTransform
import io.github.tobsef.svgo.roundTransform
import io.github.tobsef.svgo.transform2js
import io.github.tobsef.svgo.transformsMultiply
import kotlin.math.max
import kotlin.math.min

/** collapses multiple transformations and optimizes it */

private class ConvertTransformOptions(
    val convertToShorts: Boolean,
    val matrixToTransform: Boolean,
    val shortTranslate: Boolean,
    val shortScale: Boolean,
    val shortRotate: Boolean,
    val removeUseless: Boolean,
    val collapseIntoOne: Boolean,
    val base: TransformParams,
)

public val convertTransform: PluginDefinition = PluginDefinition(
    "convertTransform",
    "collapses multiple transformations and optimizes it",
) { _, params, _ ->
    val options = ConvertTransformOptions(
        convertToShorts = params.bool("convertToShorts", true),
        matrixToTransform = params.bool("matrixToTransform", true),
        shortTranslate = params.bool("shortTranslate", true),
        shortScale = params.bool("shortScale", true),
        shortRotate = params.bool("shortRotate", true),
        removeUseless = params.bool("removeUseless", true),
        collapseIntoOne = params.bool("collapseIntoOne", true),
        base = TransformParams(
            floatPrecision = params.int("floatPrecision", 3),
            transformPrecision = params.int("transformPrecision", 5),
            degPrecision = params.intOrNull("degPrecision"),
            leadingZero = params.bool("leadingZero", true),
            negativeExtraSpace = params.bool("negativeExtraSpace", false),
        ),
    )

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                for (attr in listOf("transform", "gradientTransform", "patternTransform")) {
                    if (node.attributes[attr] != null) convertTransformAttribute(node, attr, options)
                }
            },
        ),
    )
}

private fun convertTransformAttribute(item: Element, attrName: String, options: ConvertTransformOptions) {
    var data = transform2js(item.attributes[attrName]!!)
    val params = definePrecision(data, options.base)

    if (options.collapseIntoOne && data.size > 1) {
        data = mutableListOf(transformsMultiply(data))
    }

    if (options.convertToShorts) {
        data = convertToShorts(data, options, params)
    } else {
        for (transform in data) roundTransform(transform, params)
    }

    if (options.removeUseless) data = removeUselessTransforms(data)

    if (data.isNotEmpty()) {
        item.attributes[attrName] = js2transform(data, params)
    } else {
        item.attributes.remove(attrName)
    }
}

private fun floatDigits(n: Double): Int {
    val text = jsNumberToString(n)
    val dot = text.indexOf('.')
    return if (dot >= 0) text.length - dot - 1 else 0
}

private val NON_DIGITS = Regex("""\D+""")

private fun digitCount(n: Double): Int = NON_DIGITS.replace(jsNumberToString(n), "").length

private fun definePrecision(data: List<Transform>, base: TransformParams): TransformParams {
    val params = base.copy()
    val matrixData = ArrayList<Double>()
    for (item in data) {
        if (item.name == "matrix") matrixData.addAll(item.data.subList(0, min(4, item.data.size)))
    }
    var numberOfDigits = params.transformPrecision
    if (matrixData.isNotEmpty()) {
        val floatDigitsMax = matrixData.maxOf { floatDigits(it) }
        params.transformPrecision = min(
            params.transformPrecision,
            if (floatDigitsMax != 0) floatDigitsMax else params.transformPrecision,
        )
        numberOfDigits = matrixData.maxOf { digitCount(it) }
    }
    if (params.degPrecision == null) {
        params.degPrecision = max(0, min(params.floatPrecision, numberOfDigits - 2))
    }
    return params
}

private fun convertToShorts(
    transforms: MutableList<Transform>,
    options: ConvertTransformOptions,
    params: TransformParams,
): MutableList<Transform> {
    var i = 0
    while (i < transforms.size) {
        var transform = transforms[i]

        if (options.matrixToTransform && transform.name == "matrix") {
            val decomposed = matrixToTransform(transform, params)
            if (js2transform(decomposed, params).length <= js2transform(listOf(transform), params).length) {
                transforms.removeAt(i)
                transforms.addAll(i, decomposed)
            }
            transform = transforms[i]
        }

        roundTransform(transform, params)

        if (options.shortTranslate && transform.name == "translate" &&
            transform.data.size == 2 && transform.data[1] == 0.0
        ) {
            transform.data.removeAt(transform.data.size - 1)
        }

        if (options.shortScale && transform.name == "scale" &&
            transform.data.size == 2 && transform.data[0] == transform.data[1]
        ) {
            transform.data.removeAt(transform.data.size - 1)
        }

        if (options.shortRotate && i - 2 >= 0 &&
            transforms[i - 2].name == "translate" &&
            transforms[i - 1].name == "rotate" &&
            transforms[i].name == "translate" &&
            transforms[i - 2].data[0] == -transforms[i].data[0] &&
            transforms[i - 2].data[1] == -transforms[i].data[1]
        ) {
            val merged = Transform(
                "rotate",
                mutableListOf(
                    transforms[i - 1].data[0],
                    transforms[i - 2].data[0],
                    transforms[i - 2].data[1],
                ),
            )
            repeat(3) { transforms.removeAt(i - 2) }
            transforms.add(i - 2, merged)
            i -= 2
        }
        i++
    }

    return transforms
}

private fun removeUselessTransforms(transforms: List<Transform>): MutableList<Transform> {
    val result = ArrayList<Transform>()
    for (transform in transforms) {
        val name = transform.name
        val data = transform.data
        val useless =
            (
                name in setOf("translate", "rotate", "skewX", "skewY") &&
                    (data.size == 1 || name == "rotate") && data[0] == 0.0
                ) ||
                (name == "translate" && data[0] == 0.0 && (data.size < 2 || data[1] == 0.0)) ||
                (name == "scale" && data[0] == 1.0 && (data.size < 2 || data[1] == 1.0)) ||
                (
                    name == "matrix" && data[0] == 1.0 && data[3] == 1.0 &&
                        data[1] == 0.0 && data[2] == 0.0 && data[4] == 0.0 && data[5] == 0.0
                    )
        if (!useless) result.add(transform)
    }
    return result
}
