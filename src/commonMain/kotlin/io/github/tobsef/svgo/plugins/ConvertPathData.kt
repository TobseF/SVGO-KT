package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.OutDataParams
import io.github.tobsef.svgo.PathItem
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.cleanupOutData
import io.github.tobsef.svgo.collectStylesheet
import io.github.tobsef.svgo.computeStyle
import io.github.tobsef.svgo.js2path
import io.github.tobsef.svgo.jsRound
import io.github.tobsef.svgo.jsToFixed
import io.github.tobsef.svgo.path2js
import io.github.tobsef.svgo.pathElems
import io.github.tobsef.svgo.toFixed
import io.github.tobsef.svgo.visit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** optimizes path data: writes in shorter form, applies transformations */

/** Rounding context shared by the path-data helpers (mirrors the module-level state in JS). */
private class PathContext {
    var precision: Int? = 3
    var error: Double = 0.0
    var arcThreshold: Double = 2.5
    var arcTolerance: Double = 0.5
    var strongRounding: Boolean = true

    fun roundData(data: MutableList<Double>): MutableList<Double> =
        if (strongRounding) strongRound(data) else roundPlain(data)

    private fun strongRound(data: MutableList<Double>): MutableList<Double> {
        val precisionNum = precision ?: 0
        var i = data.size - 1
        while (i >= 0) {
            val fixed = toFixed(data[i], precisionNum)
            if (fixed != data[i]) {
                val rounded = toFixed(data[i], precisionNum - 1)
                data[i] = if (toFixed(abs(rounded - data[i]), precisionNum + 1) >= error) fixed else rounded
            }
            i--
        }
        return data
    }

    private fun roundPlain(data: MutableList<Double>): MutableList<Double> {
        var i = data.size - 1
        while (i >= 0) {
            data[i] = jsRound(data[i])
            i--
        }
        return data
    }
}

private val ctx = PathContext()

/** JS array indexing: out-of-range (including negative) yields NaN. */
private fun at(arr: List<Double>, index: Int): Double =
    if (index >= 0 && index < arr.size) arr[index] else Double.NaN

/**
 * ECMAScript `Array.prototype.filter` semantics: length captured once, each element read fresh (so
 * in-callback splices are observed).
 */
private fun jsFilter(
    arr: MutableList<PathItem>,
    cb: (item: PathItem, index: Int, arr: MutableList<PathItem>) -> Boolean,
): MutableList<PathItem> {
    val result = ArrayList<PathItem>()
    val length = arr.size
    var k = 0
    while (k < length) {
        if (k < arr.size) {
            val kValue = arr[k]
            if (cb(kValue, k, arr)) result.add(kValue)
        }
        k++
    }
    return result
}

/** All flags of `convertPathData`. */
private class CpdParams(
    val makeArcs: Boolean,
    val straightCurves: Boolean,
    val convertToQ: Boolean,
    val lineShorthands: Boolean,
    val convertToZ: Boolean,
    val curveSmoothShorthands: Boolean,
    val smartArcRounding: Boolean,
    val removeUseless: Boolean,
    val collapseRepeated: Boolean,
    val utilizeAbsolute: Boolean,
    val negativeExtraSpace: Boolean,
    val forceAbsolutePath: Boolean,
    val outData: OutDataParams,
)

public val convertPathData: PluginDefinition = PluginDefinition(
    "convertPathData",
    "optimizes path data: writes in shorter form, applies transformations",
) { root, params, _ ->
    val applyTransformsEnabled = params.bool("applyTransforms", true)
    val applyTransformsStroked = params.bool("applyTransformsStroked", true)
    val makeArcsParams = if (params.has("makeArcs")) params.raw("makeArcs") else mapOf(
        "threshold" to 2.5,
        "tolerance" to 0.5,
    )
    val straightCurves = params.bool("straightCurves", true)
    val convertToQ = params.bool("convertToQ", true)
    val lineShorthands = params.bool("lineShorthands", true)
    val convertToZ = params.bool("convertToZ", true)
    val curveSmoothShorthands = params.bool("curveSmoothShorthands", true)
    val floatPrecision = params.precision("floatPrecision", 3)
    val transformPrecision = params.int("transformPrecision", 5)
    val smartArcRounding = params.bool("smartArcRounding", true)
    val removeUseless = params.bool("removeUseless", true)
    val collapseRepeated = params.bool("collapseRepeated", true)
    val utilizeAbsolute = params.bool("utilizeAbsolute", true)
    val leadingZero = params.bool("leadingZero", true)
    val negativeExtraSpace = params.bool("negativeExtraSpace", true)
    val noSpaceAfterFlags = params.bool("noSpaceAfterFlags", false)
    val forceAbsolutePath = params.bool("forceAbsolutePath", false)

    @Suppress("UNCHECKED_CAST")
    val makeArcsMap = makeArcsParams as? Map<String, Any?>
    val makeArcsEnabled = io.github.tobsef.svgo.Params.isTruthy(makeArcsParams)

    val cpd = CpdParams(
        makeArcs = makeArcsEnabled,
        straightCurves = straightCurves,
        convertToQ = convertToQ,
        lineShorthands = lineShorthands,
        convertToZ = convertToZ,
        curveSmoothShorthands = curveSmoothShorthands,
        smartArcRounding = smartArcRounding,
        removeUseless = removeUseless,
        collapseRepeated = collapseRepeated,
        utilizeAbsolute = utilizeAbsolute,
        negativeExtraSpace = negativeExtraSpace,
        forceAbsolutePath = forceAbsolutePath,
        outData = OutDataParams(
            leadingZero = leadingZero,
            negativeExtraSpace = negativeExtraSpace,
            noSpaceAfterFlags = noSpaceAfterFlags,
        ),
    )

    if (applyTransformsEnabled) {
        visit(root, applyTransforms(root, transformPrecision, applyTransformsStroked))
    }

    val stylesheet = collectStylesheet(root)

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                if (node.name in pathElems && node.attributes["d"] != null) {
                    val computed = computeStyle(stylesheet, node)
                    ctx.precision = floatPrecision
                    ctx.error = if (floatPrecision != null) toFixedPow(floatPrecision) else 1e-2
                    ctx.strongRounding = floatPrecision != null && floatPrecision > 0 && floatPrecision < 20
                    if (makeArcsEnabled && makeArcsMap != null) {
                        ctx.arcThreshold = (makeArcsMap["threshold"] as? Number)?.toDouble() ?: 2.5
                        ctx.arcTolerance = (makeArcsMap["tolerance"] as? Number)?.toDouble() ?: 0.5
                    }
                    val hasMarkerMid = computed["marker-mid"] != null

                    val strokeStyle = computed["stroke"]
                    val maybeHasStroke =
                        strokeStyle != null && (strokeStyle.isDynamic || strokeStyle.value != "none")
                    val linecapStyle = computed["stroke-linecap"]
                    val maybeHasLinecap =
                        linecapStyle != null && (linecapStyle.isDynamic || linecapStyle.value != "butt")
                    val isSafeToUseZ = if (maybeHasStroke) {
                        val linejoinStyle = computed["stroke-linejoin"]
                        linecapStyle != null && linecapStyle.isStatic && linecapStyle.value == "round" &&
                            linejoinStyle != null && linejoinStyle.isStatic && linejoinStyle.value == "round"
                    } else {
                        true
                    }

                    val isSafeToRemove = { isFirstDraw: Boolean, safeIfNotFirstDraw: Boolean ->
                        when {
                            !maybeHasStroke -> true
                            isFirstDraw -> !maybeHasLinecap
                            else -> safeIfNotFirstDraw
                        }
                    }

                    var data = path2js(node)

                    if (data.isNotEmpty()) {
                        val includesVertices = data.any { it.command != "m" && it.command != "M" }
                        convertToRelative(data)
                        data = filters(data, cpd, isSafeToUseZ, isSafeToRemove, hasMarkerMid)
                        if (utilizeAbsolute) data = convertToMixed(data, cpd)

                        val hasMarker = node.attributes["marker-start"] != null ||
                            node.attributes["marker-end"] != null
                        val isMarkersOnlyPath = hasMarker && includesVertices &&
                            data.all { it.command == "m" || it.command == "M" }
                        if (isMarkersOnlyPath) data.add(PathItem("z", mutableListOf()))

                        js2path(node, data, floatPrecision, noSpaceAfterFlags)
                    }
                }
            },
        ),
    )
}

private fun toFixedPow(precision: Int): Double = jsToFixed(0.1.pow(precision), precision)

// ---------------------------------------------------------------------------

private fun convertToRelative(pathData: MutableList<PathItem>): MutableList<PathItem> {
    val start = doubleArrayOf(0.0, 0.0)
    val cursor = doubleArrayOf(0.0, 0.0)
    var prevCoords = mutableListOf(0.0, 0.0)

    for (i in pathData.indices) {
        val pathItem = pathData[i]
        var command = pathItem.command
        val args = pathItem.args

        when (command) {
            "m" -> {
                cursor[0] += args[0]; cursor[1] += args[1]
                start[0] = cursor[0]; start[1] = cursor[1]
            }
            "M" -> {
                if (i != 0) command = "m"
                args[0] -= cursor[0]; args[1] -= cursor[1]
                cursor[0] += args[0]; cursor[1] += args[1]
                start[0] = cursor[0]; start[1] = cursor[1]
            }
            "l" -> {
                cursor[0] += args[0]; cursor[1] += args[1]
            }
            "L" -> {
                command = "l"
                args[0] -= cursor[0]; args[1] -= cursor[1]
                cursor[0] += args[0]; cursor[1] += args[1]
            }
            "h" -> cursor[0] += args[0]
            "H" -> {
                command = "h"
                args[0] -= cursor[0]; cursor[0] += args[0]
            }
            "v" -> cursor[1] += args[0]
            "V" -> {
                command = "v"
                args[0] -= cursor[1]; cursor[1] += args[0]
            }
            "c" -> {
                cursor[0] += args[4]; cursor[1] += args[5]
            }
            "C" -> {
                command = "c"
                args[0] -= cursor[0]; args[1] -= cursor[1]
                args[2] -= cursor[0]; args[3] -= cursor[1]
                args[4] -= cursor[0]; args[5] -= cursor[1]
                cursor[0] += args[4]; cursor[1] += args[5]
            }
            "s" -> {
                cursor[0] += args[2]; cursor[1] += args[3]
            }
            "S" -> {
                command = "s"
                args[0] -= cursor[0]; args[1] -= cursor[1]
                args[2] -= cursor[0]; args[3] -= cursor[1]
                cursor[0] += args[2]; cursor[1] += args[3]
            }
            "q" -> {
                cursor[0] += args[2]; cursor[1] += args[3]
            }
            "Q" -> {
                command = "q"
                args[0] -= cursor[0]; args[1] -= cursor[1]
                args[2] -= cursor[0]; args[3] -= cursor[1]
                cursor[0] += args[2]; cursor[1] += args[3]
            }
            "t" -> {
                cursor[0] += args[0]; cursor[1] += args[1]
            }
            "T" -> {
                command = "t"
                args[0] -= cursor[0]; args[1] -= cursor[1]
                cursor[0] += args[0]; cursor[1] += args[1]
            }
            "a" -> {
                cursor[0] += args[5]; cursor[1] += args[6]
            }
            "A" -> {
                command = "a"
                args[5] -= cursor[0]; args[6] -= cursor[1]
                cursor[0] += args[5]; cursor[1] += args[6]
            }
            "Z", "z" -> {
                cursor[0] = start[0]; cursor[1] = start[1]
            }
        }

        pathItem.command = command
        pathItem.args = args
        pathItem.base = prevCoords
        pathItem.coords = mutableListOf(cursor[0], cursor[1])
        prevCoords = pathItem.coords!!
    }

    return pathData
}

private fun data2path(params: CpdParams, pathData: List<PathItem>): String {
    val out = StringBuilder()
    for (item in pathData) {
        out.append(item.command)
        out.append(cleanupOutData(ctx.roundData(ArrayList(item.args)), params.outData))
    }
    return out.toString()
}

private fun filters(
    path: MutableList<PathItem>,
    params: CpdParams,
    isSafeToUseZ: Boolean,
    isSafeToRemove: (Boolean, Boolean) -> Boolean,
    hasMarkerMid: Boolean,
): MutableList<PathItem> {
    val relSubpoint = mutableListOf(0.0, 0.0)
    val pathBase = mutableListOf(0.0, 0.0)
    var prev: PathItem? = null
    var prevQControlPoint: MutableList<Double>? = null

    fun stringify(data: List<PathItem>): String = data2path(params, data)

    return jsFilter(path) { item, index, pathList ->
        val qControlPoint = prevQControlPoint
        var command = item.command
        var data = item.args
        var nextItem = pathList.getOrNull(index + 1)
        var keep = true

        if (command != "Z" && command != "z") {
            var sdata = data
            if (command == "s") {
                sdata = ArrayList<Double>(data.size + 2).also {
                    it.add(0.0); it.add(0.0); it.addAll(data)
                }
                val pdata = prev!!.args
                val n = pdata.size
                // JS array out-of-bounds (incl. negative) yields undefined -> NaN
                sdata[0] = at(pdata, n - 2) - at(pdata, n - 4)
                sdata[1] = at(pdata, n - 1) - at(pdata, n - 3)
            }

            var circle: Circle? = null
            if (params.makeArcs && (command == "c" || command == "s") && isConvex(sdata)) {
                circle = findCircle(sdata)
            }
            if (circle != null) {
                val r = ctx.roundData(mutableListOf(circle.radius))[0]
                var angle = findArcAngle(sdata, circle)
                val sweep = if (sdata[5] * sdata[0] - sdata[4] * sdata[1] > 0) 1.0 else 0.0
                var arc: PathItem? = PathItem(
                    "a",
                    mutableListOf(r, r, 0.0, 0.0, sweep, sdata[4], sdata[5]),
                ).also {
                    it.coords = ArrayList(item.coords!!)
                    it.base = item.base
                }
                val output = arrayListOf(arc!!)
                val relCenter = mutableListOf(circle.center[0] - sdata[4], circle.center[1] - sdata[5])
                val relCircle = Circle(relCenter, circle.radius)
                val arcCurves = arrayListOf(item)
                var hasPrev = 0
                var suffix = ""

                val previous = prev
                if (previous != null && (
                        (previous.command == "c" && isConvex(previous.args) && isArcPrev(previous.args, circle)) ||
                            (previous.command == "a" && previous.sdata != null && isArcPrev(previous.sdata!!, circle))
                        )
                ) {
                    arcCurves.add(0, previous)
                    arc!!.base = previous.base
                    arc!!.args[5] = arc!!.coords!![0] - arc!!.base!![0]
                    arc!!.args[6] = arc!!.coords!![1] - arc!!.base!![1]
                    val prevData = if (previous.command == "a") previous.sdata!! else previous.args
                    val prevAngle = findArcAngle(
                        prevData,
                        Circle(
                            mutableListOf(
                                prevData[4] + circle.center[0],
                                prevData[5] + circle.center[1],
                            ),
                            circle.radius,
                        ),
                    )
                    angle += prevAngle
                    if (angle > PI) arc!!.args[3] = 1.0
                    hasPrev = 1
                }

                var j = index
                while (true) {
                    j++
                    nextItem = pathList.getOrNull(j)
                    val next = nextItem
                    if (!(next != null && (next.command == "c" || next.command == "s"))) break
                    var nextData = next.args
                    if (next.command == "s") {
                        val nextLonghand = makeLonghand(
                            PathItem("s", ArrayList(next.args)),
                            pathList[j - 1].args,
                        )
                        nextData = nextLonghand.args
                        nextLonghand.args = nextData.subList(0, 2).toMutableList()
                        suffix = stringify(listOf(nextLonghand))
                    }
                    if (isConvex(nextData) && isArc(nextData, relCircle)) {
                        angle += findArcAngle(nextData, relCircle)
                        if (angle - 2 * PI > 1e-3) break
                        if (angle > PI) arc!!.args[3] = 1.0
                        arcCurves.add(next)
                        if (2 * PI - angle > 1e-3) {
                            arc!!.coords = next.coords
                            arc!!.args[5] = arc!!.coords!![0] - arc!!.base!![0]
                            arc!!.args[6] = arc!!.coords!![1] - arc!!.base!![1]
                        } else {
                            arc!!.args[5] = 2 * (relCircle.center[0] - nextData[4])
                            arc!!.args[6] = 2 * (relCircle.center[1] - nextData[5])
                            arc!!.coords = mutableListOf(
                                arc!!.base!![0] + arc!!.args[5],
                                arc!!.base!![1] + arc!!.args[6],
                            )
                            arc = PathItem(
                                "a",
                                mutableListOf(
                                    r, r, 0.0, 0.0, sweep,
                                    next.coords!![0] - arc!!.coords!![0],
                                    next.coords!![1] - arc!!.coords!![1],
                                ),
                            ).also {
                                it.coords = next.coords
                                it.base = output[output.size - 1].coords
                            }
                            output.add(arc!!)
                            j++
                            break
                        }
                        relCenter[0] -= nextData[4]
                        relCenter[1] -= nextData[5]
                    } else {
                        break
                    }
                }

                if ((stringify(output) + suffix).length < stringify(arcCurves).length) {
                    if (j < pathList.size && pathList[j].command == "s") {
                        makeLonghand(pathList[j], pathList[j - 1].args)
                    }
                    if (hasPrev == 1) {
                        val prevArc = output.removeAt(0)
                        ctx.roundData(prevArc.args)
                        relSubpoint[0] += prevArc.args[5] - prev!!.args[prev!!.args.size - 2]
                        relSubpoint[1] += prevArc.args[6] - prev!!.args[prev!!.args.size - 1]
                        prev!!.command = "a"
                        prev!!.args = prevArc.args
                        item.base = prevArc.coords
                        prev!!.coords = prevArc.coords
                    }
                    arc = if (output.isNotEmpty()) output.removeAt(0) else null
                    if (arcCurves.size == 1) {
                        item.sdata = ArrayList(sdata)
                    } else if (arcCurves.size - 1 - hasPrev > 0) {
                        val count = arcCurves.size - 1 - hasPrev
                        val from = index + 1
                        val to = min(from + count, pathList.size)
                        val replacement = ArrayList<PathItem>(pathList.subList(0, from))
                        replacement.addAll(output)
                        replacement.addAll(pathList.subList(to, pathList.size))
                        pathList.clear()
                        pathList.addAll(replacement)
                    }
                    if (arc == null) return@jsFilter false
                    command = "a"
                    data = arc.args
                    item.coords = arc.coords
                }
            }

            if (ctx.precision != null) {
                when (command) {
                    "m", "l", "t", "q", "s", "c" ->
                        for (k in data.indices.reversed()) {
                            data[k] += item.base!![k % 2] - relSubpoint[k % 2]
                        }
                    "h" -> data[0] += item.base!![0] - relSubpoint[0]
                    "v" -> data[0] += item.base!![1] - relSubpoint[1]
                    "a" -> {
                        data[5] += item.base!![0] - relSubpoint[0]
                        data[6] += item.base!![1] - relSubpoint[1]
                    }
                }
                ctx.roundData(data)

                when (command) {
                    "h" -> relSubpoint[0] += data[0]
                    "v" -> relSubpoint[1] += data[0]
                    else -> {
                        relSubpoint[0] += data[data.size - 2]
                        relSubpoint[1] += data[data.size - 1]
                    }
                }
                ctx.roundData(relSubpoint)

                if (command == "M" || command == "m") {
                    pathBase[0] = relSubpoint[0]
                    pathBase[1] = relSubpoint[1]
                }
            }

            val sagitta = if (command == "a") calculateSagitta(data) else null
            if (params.smartArcRounding && sagitta != null && ctx.precision != null && ctx.precision != 0) {
                var precisionNew = ctx.precision!!
                while (precisionNew >= 0) {
                    val radius = toFixed(data[0], precisionNew)
                    val candidate = ArrayList<Double>()
                    candidate.add(radius)
                    candidate.add(radius)
                    candidate.addAll(data.subList(2, data.size))
                    val sagittaNew = calculateSagitta(candidate)
                    if (sagittaNew != null && abs(sagitta - sagittaNew) < ctx.error) {
                        data[0] = radius
                        data[1] = radius
                    } else {
                        break
                    }
                    precisionNew--
                }
            }

            if (params.straightCurves) {
                if ((command == "c" && isCurveStraightLine(data)) ||
                    (command == "s" && isCurveStraightLine(sdata))
                ) {
                    if (nextItem != null && nextItem.command == "s") makeLonghand(nextItem, data)
                    command = "l"
                    data = data.subList(data.size - 2, data.size).toMutableList()
                } else if ((command == "q" && isCurveStraightLine(data)) ||
                    (command == "t" && prev?.command != "q" && prev?.command != "t")
                ) {
                    if (command == "q" && nextItem != null && nextItem.command == "t") {
                        makeLonghand(nextItem, data)
                    }
                    if (command == "t" && nextItem != null && nextItem.command == "t") {
                        nextItem.command = "q"
                        nextItem.args.addAll(
                            0,
                            listOf(
                                (2 * item.coords!![0] - item.base!![0]) - item.coords!![0],
                                (2 * item.coords!![1] - item.base!![1]) - item.coords!![1],
                            ),
                        )
                    }
                    command = "l"
                    data = data.subList(data.size - 2, data.size).toMutableList()
                } else if (command == "a" &&
                    (data[0] == 0.0 || data[1] == 0.0 || (sagitta != null && sagitta < ctx.error))
                ) {
                    command = "l"
                    data = data.subList(data.size - 2, data.size).toMutableList()
                }
            }

            if (params.convertToQ && command == "c") {
                val x1 = 0.75 * (item.base!![0] + data[0]) - 0.25 * item.base!![0]
                val x2 = 0.75 * (item.base!![0] + data[2]) - 0.25 * (item.base!![0] + data[4])
                if (abs(x1 - x2) < ctx.error * 2) {
                    val y1 = 0.75 * (item.base!![1] + data[1]) - 0.25 * item.base!![1]
                    val y2 = 0.75 * (item.base!![1] + data[3]) - 0.25 * (item.base!![1] + data[5])
                    if (abs(y1 - y2) < ctx.error * 2) {
                        val newData = ArrayList(data)
                        newData[0] = x1 + x2 - item.base!![0]
                        newData[1] = y1 + y2 - item.base!![1]
                        newData.removeAt(3)
                        newData.removeAt(2)
                        ctx.roundData(newData)
                        val originalLength = cleanupOutData(data, params.outData).length
                        val newLength = cleanupOutData(newData, params.outData).length
                        if (newLength < originalLength) {
                            command = "q"
                            data = newData
                            if (nextItem != null && nextItem.command == "s") makeLonghand(nextItem, data)
                        }
                    }
                }
            }

            if (params.lineShorthands && command == "l") {
                if (data[1] == 0.0) {
                    command = "h"
                    data = data.subList(0, data.size - 1).toMutableList()
                } else if (data[0] == 0.0) {
                    command = "v"
                    data = data.subList(1, data.size).toMutableList()
                }
            }

            if (params.collapseRepeated && !hasMarkerMid &&
                (command == "m" || command == "h" || command == "v") &&
                prev?.command != null && command == prev!!.command.lowercase() &&
                (
                    (command != "h" && command != "v") ||
                        ((prev!!.args[0] >= 0) == (data[0] >= 0))
                    )
            ) {
                prev!!.args[0] += data[0]
                if (command != "h" && command != "v") prev!!.args[1] += data[1]
                prev!!.coords = item.coords
                pathList[index] = prev!!
                return@jsFilter false
            }

            if (params.curveSmoothShorthands && prev?.command != null) {
                val previous = prev!!
                if (command == "c") {
                    if (previous.command == "c" &&
                        abs(data[0] - -(previous.args[2] - previous.args[4])) < ctx.error &&
                        abs(data[1] - -(previous.args[3] - previous.args[5])) < ctx.error
                    ) {
                        command = "s"; data = data.subList(2, data.size).toMutableList()
                    } else if (previous.command == "s" &&
                        abs(data[0] - -(previous.args[0] - previous.args[2])) < ctx.error &&
                        abs(data[1] - -(previous.args[1] - previous.args[3])) < ctx.error
                    ) {
                        command = "s"; data = data.subList(2, data.size).toMutableList()
                    } else if (previous.command != "c" && previous.command != "s" &&
                        abs(data[0]) < ctx.error && abs(data[1]) < ctx.error
                    ) {
                        command = "s"; data = data.subList(2, data.size).toMutableList()
                    }
                } else if (command == "q") {
                    if (previous.command == "q" &&
                        abs(data[0] - (previous.args[2] - previous.args[0])) < ctx.error &&
                        abs(data[1] - (previous.args[3] - previous.args[1])) < ctx.error
                    ) {
                        command = "t"; data = data.subList(2, data.size).toMutableList()
                    } else if (previous.command == "t") {
                        val predicted = reflectPoint(qControlPoint!!, item.base!!)
                        val real = listOf(data[0] + item.base!![0], data[1] + item.base!![1])
                        if (abs(predicted[0] - real[0]) < ctx.error && abs(predicted[1] - real[1]) < ctx.error) {
                            command = "t"; data = data.subList(2, data.size).toMutableList()
                        }
                    }
                }
            }

            if (params.removeUseless &&
                isSafeToRemove(prev?.command == "m" || prev?.command == "M", true)
            ) {
                if (command in setOf("l", "h", "v", "q", "t", "c", "s") && data.all { it == 0.0 }) {
                    pathList[index] = prev!!
                    return@jsFilter false
                }
                if (command == "a" && data[5] == 0.0 && data[6] == 0.0) {
                    pathList[index] = prev!!
                    return@jsFilter false
                }
            }

            if (params.convertToZ &&
                (isSafeToUseZ || (nextItem != null && (nextItem.command == "Z" || nextItem.command == "z"))) &&
                (command == "l" || command == "h" || command == "v")
            ) {
                if (abs(pathBase[0] - item.coords!![0]) < ctx.error &&
                    abs(pathBase[1] - item.coords!![1]) < ctx.error
                ) {
                    command = "z"
                    data = mutableListOf()
                }
            }

            item.command = command
            item.args = data
        } else {
            relSubpoint[0] = pathBase[0]
            relSubpoint[1] = pathBase[1]
            if (prev?.command == "Z" || prev?.command == "z") return@jsFilter false
        }

        if ((command == "Z" || command == "z") && params.removeUseless &&
            isSafeToRemove(prev?.command == "m" || prev?.command == "M", isSafeToUseZ) &&
            abs(item.base!![0] - item.coords!![0]) < ctx.error / 10 &&
            abs(item.base!![1] - item.coords!![1]) < ctx.error / 10
        ) {
            return@jsFilter false
        }

        prevQControlPoint = when {
            command == "q" -> mutableListOf(data[0] + item.base!![0], data[1] + item.base!![1])
            command == "t" -> if (qControlPoint != null) {
                reflectPoint(qControlPoint, item.base!!)
            } else {
                item.coords
            }
            else -> null
        }
        prev = item
        keep
    }
}

private fun convertToMixed(path: MutableList<PathItem>, params: CpdParams): MutableList<PathItem> {
    var prev = path[0]

    return jsFilter(path) { item, index, _ ->
        if (index == 0) {
            true
        } else if (item.command == "Z" || item.command == "z") {
            prev = item
            true
        } else {
            val command = item.command
            val data = item.args
            val adata = ArrayList(data)
            val rdata = ArrayList(data)

            when (command) {
                "m", "l", "t", "q", "s", "c" ->
                    for (i in adata.indices.reversed()) adata[i] += item.base!![i % 2]
                "h" -> adata[0] += item.base!![0]
                "v" -> adata[0] += item.base!![1]
                "a" -> {
                    adata[5] += item.base!![0]
                    adata[6] += item.base!![1]
                }
            }

            ctx.roundData(adata)
            ctx.roundData(rdata)

            val absoluteStr = cleanupOutData(adata, params.outData)
            val relativeStr = cleanupOutData(rdata, params.outData)

            if (params.forceAbsolutePath || (
                    absoluteStr.length < relativeStr.length &&
                        !(
                            params.negativeExtraSpace &&
                                command == prev.command &&
                                prev.command[0].code > 96 &&
                                absoluteStr.length == relativeStr.length - 1 &&
                                (
                                    data[0] < 0 ||
                                        (
                                            floor(data[0]) == 0.0 && data[0] != floor(data[0]) &&
                                                prev.args[prev.args.size - 1] % 1.0 != 0.0
                                            )
                                    )
                            )
                    )
            ) {
                item.command = command.uppercase()
                item.args = adata
            }

            prev = item
            true
        }
    }
}

// ---------------------------------------------------------------------------
// geometry helpers
// ---------------------------------------------------------------------------

private class Circle(val center: MutableList<Double>, val radius: Double)

private fun isConvex(data: List<Double>): Boolean {
    val center = getIntersection(
        listOf(0.0, 0.0, data[2], data[3], data[0], data[1], data[4], data[5]),
    ) ?: return false
    return (data[2] < center[0]) == (center[0] < 0) &&
        (data[3] < center[1]) == (center[1] < 0) &&
        (data[4] < center[0]) == (center[0] < data[0]) &&
        (data[5] < center[1]) == (center[1] < data[1])
}

private fun getIntersection(coords: List<Double>): List<Double>? {
    val a1 = coords[1] - coords[3]
    val b1 = coords[2] - coords[0]
    val c1 = coords[0] * coords[3] - coords[2] * coords[1]
    val a2 = coords[5] - coords[7]
    val b2 = coords[6] - coords[4]
    val c2 = coords[4] * coords[7] - coords[5] * coords[6]
    val denom = a1 * b2 - a2 * b1
    if (denom == 0.0) return null
    val cross = listOf((b1 * c2 - b2 * c1) / denom, (a1 * c2 - a2 * c1) / -denom)
    if (!cross[0].isNaN() && !cross[1].isNaN() && cross[0].isFinite() && cross[1].isFinite()) return cross
    return null
}

private fun isCurveStraightLine(data: List<Double>): Boolean {
    var i = data.size - 2
    val a = -data[i + 1]
    val b = data[i]
    val denom = a * a + b * b
    if (i <= 1 || denom == 0.0 || !(1 / denom).isFinite()) return false
    val d = 1 / denom
    i -= 2
    while (i >= 0) {
        if (sqrt((a * data[i] + b * data[i + 1]).pow(2) * d) > ctx.error) return false
        i -= 2
    }
    return true
}

private fun calculateSagitta(data: List<Double>): Double? {
    if (data[3] == 1.0) return null
    val rx = data[0]
    val ry = data[1]
    if (abs(rx - ry) > ctx.error) return null
    val chord = hypot(data[5], data[6])
    if (chord > rx * 2) return null
    return rx - sqrt(rx.pow(2) - 0.25 * chord.pow(2))
}

private fun makeLonghand(item: PathItem, data: List<Double>): PathItem {
    when (item.command) {
        "s" -> item.command = "c"
        "t" -> item.command = "q"
    }
    item.args.addAll(
        0,
        listOf(
            data[data.size - 2] - data[data.size - 4],
            data[data.size - 1] - data[data.size - 3],
        ),
    )
    return item
}

private fun getDistance(p1: List<Double>, p2: List<Double>): Double = hypot(p1[0] - p2[0], p1[1] - p2[1])

private fun reflectPoint(controlPoint: List<Double>, base: List<Double>): MutableList<Double> =
    mutableListOf(2 * base[0] - controlPoint[0], 2 * base[1] - controlPoint[1])

private fun getCubicBezierPoint(curve: List<Double>, t: Double): List<Double> {
    val sqrT = t * t
    val cubT = sqrT * t
    val mt = 1 - t
    val sqrMt = mt * mt
    return listOf(
        3 * sqrMt * t * curve[0] + 3 * mt * sqrT * curve[2] + cubT * curve[4],
        3 * sqrMt * t * curve[1] + 3 * mt * sqrT * curve[3] + cubT * curve[5],
    )
}

private fun findCircle(curve: List<Double>): Circle? {
    val midPoint = getCubicBezierPoint(curve, 0.5)
    val m1 = listOf(midPoint[0] / 2, midPoint[1] / 2)
    val m2 = listOf((midPoint[0] + curve[4]) / 2, (midPoint[1] + curve[5]) / 2)
    val center = getIntersection(
        listOf(
            m1[0], m1[1], m1[0] + m1[1], m1[1] - m1[0],
            m2[0], m2[1], m2[0] + (m2[1] - midPoint[1]), m2[1] - (m2[0] - midPoint[0]),
        ),
    ) ?: return null
    val radius = getDistance(listOf(0.0, 0.0), center)
    val tolerance = min(ctx.arcThreshold * ctx.error, (ctx.arcTolerance * radius) / 100)
    if (radius < 1e15 && listOf(0.25, 0.75).all {
            abs(getDistance(getCubicBezierPoint(curve, it), center) - radius) <= tolerance
        }
    ) {
        return Circle(center.toMutableList(), radius)
    }
    return null
}

private fun isArc(curve: List<Double>, circle: Circle): Boolean {
    val tolerance = min(ctx.arcThreshold * ctx.error, (ctx.arcTolerance * circle.radius) / 100)
    return listOf(0.0, 0.25, 0.5, 0.75, 1.0).all {
        abs(getDistance(getCubicBezierPoint(curve, it), circle.center) - circle.radius) <= tolerance
    }
}

private fun isArcPrev(curve: List<Double>, circle: Circle): Boolean = isArc(
    curve,
    Circle(mutableListOf(circle.center[0] + curve[4], circle.center[1] + curve[5]), circle.radius),
)

private fun findArcAngle(curve: List<Double>, relCircle: Circle): Double {
    val x1 = -relCircle.center[0]
    val y1 = -relCircle.center[1]
    val x2 = curve[4] - relCircle.center[0]
    val y2 = curve[5] - relCircle.center[1]
    val value = (x1 * x2 + y1 * y2) / sqrt((x1 * x1 + y1 * y1) * (x2 * x2 + y2 * y2))
    return acos(maxOf(-1.0, min(1.0, value)))
}
