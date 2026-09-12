package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.PathItem
import io.github.tobsef.svgo.Root
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.attrsGroupsDefaults
import io.github.tobsef.svgo.collectStylesheet
import io.github.tobsef.svgo.computeStyle
import io.github.tobsef.svgo.includesUrlReference
import io.github.tobsef.svgo.jsToFixed
import io.github.tobsef.svgo.path2js
import io.github.tobsef.svgo.referencesProps
import io.github.tobsef.svgo.removeLeadingZero
import io.github.tobsef.svgo.transform2js
import io.github.tobsef.svgo.transformArc
import io.github.tobsef.svgo.transformsMultiply
import kotlin.math.abs
import kotlin.math.hypot

/**
 * applies transforms to path data
 *
 * Not registered as a standalone preset-default plugin; invoked internally by `convertPathData`.
 */
private val REG_NUMERIC = Regex("""[-+]?(\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")

internal fun applyTransforms(root: Root, transformPrecision: Int, applyTransformsStroked: Boolean): Visitor {
    val stylesheet = collectStylesheet(root)

    return Visitor(
        element = Callbacks(
            enter = { node, _ ->
                val transformAttr = node.attributes["transform"]
                if (node.attributes["d"] != null &&
                    node.attributes["id"] == null &&
                    !transformAttr.isNullOrEmpty() &&
                    node.attributes["style"] == null &&
                    node.attributes.none { (name, value) ->
                        name in referencesProps && value != null && includesUrlReference(value)
                    }
                ) {
                    val computed = computeStyle(stylesheet, node)
                    val transformStyle = computed["transform"]
                    if (!(transformStyle != null && transformStyle.isStatic && transformStyle.value != transformAttr)) {
                        val matrix = transformsMultiply(transform2js(transformAttr))

                        val strokeStyle = computed["stroke"]
                        val stroke = if (strokeStyle != null && strokeStyle.isStatic) strokeStyle.value else null
                        val strokeWidthStyle = computed["stroke-width"]
                        val strokeWidth =
                            if (strokeWidthStyle != null && strokeWidthStyle.isStatic) strokeWidthStyle.value else null

                        val dynamicStroke = (strokeStyle != null && strokeStyle.isDynamic) ||
                            (strokeWidthStyle != null && strokeWidthStyle.isDynamic)

                        if (!dynamicStroke) {
                            val scale = jsToFixed(hypot(matrix.data[0], matrix.data[1]), transformPrecision)

                            var bail = false
                            if (stroke != null && stroke != "none") {
                                if (!applyTransformsStroked) {
                                    bail = true
                                } else if ((matrix.data[0] != matrix.data[3] || matrix.data[1] != -matrix.data[2]) &&
                                    (matrix.data[0] != -matrix.data[3] || matrix.data[1] != matrix.data[2])
                                ) {
                                    bail = true
                                } else if (scale != 1.0 &&
                                    node.attributes["vector-effect"] != "non-scaling-stroke"
                                ) {
                                    val baseStrokeWidth = strokeWidth
                                        ?: attrsGroupsDefaults.getValue("presentation").getValue("stroke-width")

                                    fun scaleNumbers(text: String): String = REG_NUMERIC.replace(text) {
                                        removeLeadingZero(it.value.toDouble() * scale)
                                    }

                                    node.attributes["stroke-width"] = scaleNumbers(baseStrokeWidth.trim())
                                    node.attributes["stroke-dashoffset"]?.let {
                                        node.attributes["stroke-dashoffset"] = scaleNumbers(it.trim())
                                    }
                                    node.attributes["stroke-dasharray"]?.let {
                                        node.attributes["stroke-dasharray"] = scaleNumbers(it.trim())
                                    }
                                }
                            }

                            if (!bail) {
                                applyMatrixToPathData(path2js(node), matrix.data)
                                node.attributes.remove("transform")
                            }
                        }
                    }
                }
            },
        ),
    )
}

private fun transformAbsolutePoint(matrix: List<Double>, x: Double, y: Double): DoubleArray =
    doubleArrayOf(matrix[0] * x + matrix[2] * y + matrix[4], matrix[1] * x + matrix[3] * y + matrix[5])

private fun transformRelativePoint(matrix: List<Double>, x: Double, y: Double): DoubleArray =
    doubleArrayOf(matrix[0] * x + matrix[2] * y, matrix[1] * x + matrix[3] * y)

internal fun applyMatrixToPathData(pathData: List<PathItem>, matrix: List<Double>) {
    val start = doubleArrayOf(0.0, 0.0)
    val cursor = doubleArrayOf(0.0, 0.0)

    for (pathItem in pathData) {
        var command = pathItem.command
        var args = pathItem.args

        if (command == "M") {
            cursor[0] = args[0]; cursor[1] = args[1]
            start[0] = cursor[0]; start[1] = cursor[1]
            val p = transformAbsolutePoint(matrix, args[0], args[1])
            args[0] = p[0]; args[1] = p[1]
        }
        if (command == "m") {
            cursor[0] += args[0]; cursor[1] += args[1]
            start[0] = cursor[0]; start[1] = cursor[1]
            val p = transformRelativePoint(matrix, args[0], args[1])
            args[0] = p[0]; args[1] = p[1]
        }

        if (command == "H") {
            command = "L"; args = mutableListOf(args[0], cursor[1])
        }
        if (command == "h") {
            command = "l"; args = mutableListOf(args[0], 0.0)
        }
        if (command == "V") {
            command = "L"; args = mutableListOf(cursor[0], args[0])
        }
        if (command == "v") {
            command = "l"; args = mutableListOf(0.0, args[0])
        }

        if (command == "L") {
            cursor[0] = args[0]; cursor[1] = args[1]
            val p = transformAbsolutePoint(matrix, args[0], args[1])
            args[0] = p[0]; args[1] = p[1]
        }
        if (command == "l") {
            cursor[0] += args[0]; cursor[1] += args[1]
            val p = transformRelativePoint(matrix, args[0], args[1])
            args[0] = p[0]; args[1] = p[1]
        }

        if (command == "C") {
            cursor[0] = args[4]; cursor[1] = args[5]
            val p1 = transformAbsolutePoint(matrix, args[0], args[1])
            val p2 = transformAbsolutePoint(matrix, args[2], args[3])
            val p = transformAbsolutePoint(matrix, args[4], args[5])
            args[0] = p1[0]; args[1] = p1[1]; args[2] = p2[0]; args[3] = p2[1]; args[4] = p[0]; args[5] = p[1]
        }
        if (command == "c") {
            cursor[0] += args[4]; cursor[1] += args[5]
            val p1 = transformRelativePoint(matrix, args[0], args[1])
            val p2 = transformRelativePoint(matrix, args[2], args[3])
            val p = transformRelativePoint(matrix, args[4], args[5])
            args[0] = p1[0]; args[1] = p1[1]; args[2] = p2[0]; args[3] = p2[1]; args[4] = p[0]; args[5] = p[1]
        }

        if (command == "S") {
            cursor[0] = args[2]; cursor[1] = args[3]
            val p2 = transformAbsolutePoint(matrix, args[0], args[1])
            val p = transformAbsolutePoint(matrix, args[2], args[3])
            args[0] = p2[0]; args[1] = p2[1]; args[2] = p[0]; args[3] = p[1]
        }
        if (command == "s") {
            cursor[0] += args[2]; cursor[1] += args[3]
            val p2 = transformRelativePoint(matrix, args[0], args[1])
            val p = transformRelativePoint(matrix, args[2], args[3])
            args[0] = p2[0]; args[1] = p2[1]; args[2] = p[0]; args[3] = p[1]
        }

        if (command == "Q") {
            cursor[0] = args[2]; cursor[1] = args[3]
            val p1 = transformAbsolutePoint(matrix, args[0], args[1])
            val p = transformAbsolutePoint(matrix, args[2], args[3])
            args[0] = p1[0]; args[1] = p1[1]; args[2] = p[0]; args[3] = p[1]
        }
        if (command == "q") {
            cursor[0] += args[2]; cursor[1] += args[3]
            val p1 = transformRelativePoint(matrix, args[0], args[1])
            val p = transformRelativePoint(matrix, args[2], args[3])
            args[0] = p1[0]; args[1] = p1[1]; args[2] = p[0]; args[3] = p[1]
        }

        if (command == "T") {
            cursor[0] = args[0]; cursor[1] = args[1]
            val p = transformAbsolutePoint(matrix, args[0], args[1])
            args[0] = p[0]; args[1] = p[1]
        }
        if (command == "t") {
            cursor[0] += args[0]; cursor[1] += args[1]
            val p = transformRelativePoint(matrix, args[0], args[1])
            args[0] = p[0]; args[1] = p[1]
        }

        if (command == "A") {
            transformArc(cursor, args, matrix)
            cursor[0] = args[5]; cursor[1] = args[6]
            if (abs(args[2]) > 80) {
                val a = args[0]
                val rotation = args[2]
                args[0] = args[1]
                args[1] = a
                args[2] = rotation + (if (rotation > 0) -90.0 else 90.0)
            }
            val p = transformAbsolutePoint(matrix, args[5], args[6])
            args[5] = p[0]; args[6] = p[1]
        }
        if (command == "a") {
            transformArc(doubleArrayOf(0.0, 0.0), args, matrix)
            cursor[0] += args[5]; cursor[1] += args[6]
            if (abs(args[2]) > 80) {
                val a = args[0]
                val rotation = args[2]
                args[0] = args[1]
                args[1] = a
                args[2] = rotation + (if (rotation > 0) -90.0 else 90.0)
            }
            val p = transformRelativePoint(matrix, args[5], args[6])
            args[5] = p[0]; args[6] = p[1]
        }

        if (command == "z" || command == "Z") {
            cursor[0] = start[0]; cursor[1] = start[1]
        }

        pathItem.command = command
        pathItem.args = args
    }
}
