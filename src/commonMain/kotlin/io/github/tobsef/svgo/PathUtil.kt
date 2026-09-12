package io.github.tobsef.svgo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Path helpers for convertPathData/mergePaths. Port of `plugins/_path.js`. */

// Shared control point state (mirrors the module-level `prevCtrlPoint` in JS).
private var prevCtrlPoint = doubleArrayOf(0.0, 0.0)

internal fun path2js(path: Element): MutableList<PathItem> {
    path.pathJS?.let { return it }
    val pathData = ArrayList<PathItem>()
    for (item in parsePathData(path.attributes["d"] ?: "")) {
        pathData.add(PathItem(item.command, item.args))
    }
    if (pathData.isNotEmpty() && pathData[0].command == "m") pathData[0].command = "M"
    path.pathJS = pathData
    return pathData
}

private fun convertRelativeToAbsolute(data: List<PathItem>): List<PathItem> {
    val newData = ArrayList<PathItem>()
    val start = doubleArrayOf(0.0, 0.0)
    val cursor = doubleArrayOf(0.0, 0.0)
    for (item in data) {
        var command = item.command
        val args = ArrayList(item.args)
        if (command == "m") {
            args[0] += cursor[0]; args[1] += cursor[1]; command = "M"
        }
        if (command == "M") {
            cursor[0] = args[0]; cursor[1] = args[1]
            start[0] = cursor[0]; start[1] = cursor[1]
        }
        if (command == "h") {
            args[0] += cursor[0]; command = "H"
        }
        if (command == "H") cursor[0] = args[0]
        if (command == "v") {
            args[0] += cursor[1]; command = "V"
        }
        if (command == "V") cursor[1] = args[0]
        if (command == "l") {
            args[0] += cursor[0]; args[1] += cursor[1]; command = "L"
        }
        if (command == "L") {
            cursor[0] = args[0]; cursor[1] = args[1]
        }
        if (command == "c") {
            args[0] += cursor[0]; args[1] += cursor[1]
            args[2] += cursor[0]; args[3] += cursor[1]
            args[4] += cursor[0]; args[5] += cursor[1]
            command = "C"
        }
        if (command == "C") {
            cursor[0] = args[4]; cursor[1] = args[5]
        }
        if (command == "s") {
            args[0] += cursor[0]; args[1] += cursor[1]
            args[2] += cursor[0]; args[3] += cursor[1]
            command = "S"
        }
        if (command == "S") {
            cursor[0] = args[2]; cursor[1] = args[3]
        }
        if (command == "q") {
            args[0] += cursor[0]; args[1] += cursor[1]
            args[2] += cursor[0]; args[3] += cursor[1]
            command = "Q"
        }
        if (command == "Q") {
            cursor[0] = args[2]; cursor[1] = args[3]
        }
        if (command == "t") {
            args[0] += cursor[0]; args[1] += cursor[1]; command = "T"
        }
        if (command == "T") {
            cursor[0] = args[0]; cursor[1] = args[1]
        }
        if (command == "a") {
            args[5] += cursor[0]; args[6] += cursor[1]; command = "A"
        }
        if (command == "A") {
            cursor[0] = args[5]; cursor[1] = args[6]
        }
        if (command == "z" || command == "Z") {
            cursor[0] = start[0]; cursor[1] = start[1]; command = "z"
        }
        newData.add(PathItem(command, args))
    }
    return newData
}

internal fun js2path(path: Element, data: MutableList<PathItem>, floatPrecision: Int?, noSpaceAfterFlags: Boolean) {
    path.pathJS = data
    val pathData = ArrayList<PathItem>()
    for (item in data) {
        if (pathData.isNotEmpty() && (item.command == "M" || item.command == "m")) {
            val last = pathData[pathData.size - 1]
            if (last.command == "M" || last.command == "m") pathData.removeAt(pathData.size - 1)
        }
        pathData.add(PathItem(item.command, item.args))
    }
    path.attributes["d"] = stringifyPathData(pathData, floatPrecision, noSpaceAfterFlags)
}

// ---------------------------------------------------------------------------
// GJK-based intersection test
// ---------------------------------------------------------------------------

private fun setVector(dest: DoubleArray, source: DoubleArray): DoubleArray {
    dest[0] = source[source.size - 2]
    dest[1] = source[source.size - 1]
    return dest
}

private fun minus(v: DoubleArray) = doubleArrayOf(-v[0], -v[1])

private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1])

private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1]

private fun orth(v: DoubleArray, from: DoubleArray): DoubleArray {
    val o = doubleArrayOf(-v[1], v[0])
    return if (dot(o, minus(from)) < 0) minus(o) else o
}

private fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray): Double =
    (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

/** A point list with the indices of its extreme points. */
private class Polygon {
    val list = ArrayList<DoubleArray>()
    var minX = 0
    var minY = 0
    var maxX = 0
    var maxY = 0
}

/** All sub-paths of a path plus the overall (value) bounding box. */
private class Points {
    val list = ArrayList<Polygon>()
    var minX = 0.0
    var minY = 0.0
    var maxX = 0.0
    var maxY = 0.0
}

internal fun intersects(path1: List<PathItem>, path2: List<PathItem>): Boolean {
    val points1 = gatherPoints(convertRelativeToAbsolute(path1))
    val points2 = gatherPoints(convertRelativeToAbsolute(path2))

    fun aabbAll(): Boolean {
        for (set1 in points1.list) {
            for (set2 in points2.list) {
                if (!(
                        set1.list[set1.maxX][0] <= set2.list[set2.minX][0] ||
                            set2.list[set2.maxX][0] <= set1.list[set1.minX][0] ||
                            set1.list[set1.maxY][1] <= set2.list[set2.minY][1] ||
                            set2.list[set2.maxY][1] <= set1.list[set1.minY][1]
                        )
                ) {
                    return false
                }
            }
        }
        return true
    }

    if (points1.maxX <= points2.minX ||
        points2.maxX <= points1.minX ||
        points1.maxY <= points2.minY ||
        points2.maxY <= points1.minY ||
        aabbAll()
    ) {
        return false
    }

    val hullNest1 = points1.list.map { convexHull(it) }
    val hullNest2 = points2.list.map { convexHull(it) }

    fun supportPoint(polygon: Polygon, direction: DoubleArray): DoubleArray {
        var index = if (direction[1] >= 0) {
            if (direction[0] < 0) polygon.maxY else polygon.maxX
        } else {
            if (direction[0] < 0) polygon.minX else polygon.minY
        }
        var max = Double.NEGATIVE_INFINITY
        while (true) {
            val value = dot(polygon.list[index], direction)
            if (value > max) {
                max = value
                index = (index + 1) % polygon.list.size
            } else {
                break
            }
        }
        return polygon.list[(if (index == 0) polygon.list.size else index) - 1]
    }

    fun getSupport(a: Polygon, b: Polygon, direction: DoubleArray): DoubleArray =
        sub(supportPoint(a, direction), supportPoint(b, minus(direction)))

    for (hull1 in hullNest1) {
        if (hull1.list.size < 3) continue
        for (hull2 in hullNest2) {
            if (hull2.list.size < 3) continue
            val simplex = arrayListOf(getSupport(hull1, hull2, doubleArrayOf(1.0, 0.0)))
            val direction = minus(simplex[0])
            var iterations = 10000
            while (true) {
                iterations--
                if (iterations == 0) return true
                simplex.add(getSupport(hull1, hull2, direction))
                if (dot(direction, simplex[simplex.size - 1]) <= 0) break
                if (processSimplex(simplex, direction)) return true
            }
        }
    }
    return false
}

private fun processSimplex(simplex: MutableList<DoubleArray>, direction: DoubleArray): Boolean {
    if (simplex.size == 2) {
        val a = simplex[1]
        val b = simplex[0]
        val ao = minus(simplex[1])
        val ab = sub(b, a)
        if (dot(ao, ab) > 0) {
            setVector(direction, orth(ab, a))
        } else {
            setVector(direction, ao)
            simplex.removeAt(0)
        }
    } else {
        val a = simplex[2]
        val b = simplex[1]
        val c = simplex[0]
        val ab = sub(b, a)
        val ac = sub(c, a)
        val ao = minus(a)
        val acb = orth(ab, ac)
        val abc = orth(ac, ab)
        if (dot(acb, ao) > 0) {
            if (dot(ab, ao) > 0) {
                setVector(direction, acb)
                simplex.removeAt(0)
            } else {
                setVector(direction, ao)
                simplex.removeAt(0)
                simplex.removeAt(0)
            }
        } else if (dot(abc, ao) > 0) {
            if (dot(ac, ao) > 0) {
                setVector(direction, abc)
                simplex.removeAt(1)
            } else {
                setVector(direction, ao)
                simplex.removeAt(0)
                simplex.removeAt(0)
            }
        } else {
            return true
        }
    }
    return false
}

private fun gatherPoints(pathData: List<PathItem>): Points {
    val points = Points()

    fun addPoint(path: Polygon, point: DoubleArray) {
        if (path.list.isEmpty() || point[1] > path.list[path.maxY][1]) {
            path.maxY = path.list.size
            points.maxY = if (points.list.isNotEmpty()) maxOf(point[1], points.maxY) else point[1]
        }
        if (path.list.isEmpty() || point[0] > path.list[path.maxX][0]) {
            path.maxX = path.list.size
            points.maxX = if (points.list.isNotEmpty()) maxOf(point[0], points.maxX) else point[0]
        }
        if (path.list.isEmpty() || point[1] < path.list[path.minY][1]) {
            path.minY = path.list.size
            points.minY = if (points.list.isNotEmpty()) min(point[1], points.minY) else point[1]
        }
        if (path.list.isEmpty() || point[0] < path.list[path.minX][0]) {
            path.minX = path.list.size
            points.minX = if (points.list.isNotEmpty()) min(point[0], points.minX) else point[0]
        }
        path.list.add(point)
    }

    for (i in pathData.indices) {
        val pathDataItem = pathData[i]
        var subPath = if (points.list.isEmpty()) Polygon() else points.list[points.list.size - 1]
        val previous = if (i == 0) null else pathData[i - 1]
        var basePoint: DoubleArray? = if (subPath.list.isEmpty()) null else subPath.list[subPath.list.size - 1]
        val data = pathDataItem.args
        var ctrlPoint = basePoint

        fun toAbsolute(n: Double, index: Int): Double =
            n + (basePoint?.get(index % 2) ?: 0.0)

        when (pathDataItem.command) {
            "M" -> {
                subPath = Polygon()
                points.list.add(subPath)
            }

            "H" -> if (basePoint != null) addPoint(subPath, doubleArrayOf(data[0], basePoint[1]))

            "V" -> if (basePoint != null) addPoint(subPath, doubleArrayOf(basePoint[0], data[0]))

            "Q" -> {
                addPoint(subPath, doubleArrayOf(data[0], data[1]))
                prevCtrlPoint = doubleArrayOf(data[2] - data[0], data[3] - data[1])
            }

            "T" -> {
                if (basePoint != null && previous != null && (previous.command == "Q" || previous.command == "T")) {
                    ctrlPoint = doubleArrayOf(
                        basePoint[0] + prevCtrlPoint[0],
                        basePoint[1] + prevCtrlPoint[1],
                    )
                    addPoint(subPath, ctrlPoint)
                    prevCtrlPoint = doubleArrayOf(data[0] - ctrlPoint[0], data[1] - ctrlPoint[1])
                }
            }

            "C" -> {
                if (basePoint != null) {
                    addPoint(
                        subPath,
                        doubleArrayOf(0.5 * (basePoint[0] + data[0]), 0.5 * (basePoint[1] + data[1])),
                    )
                }
                addPoint(subPath, doubleArrayOf(0.5 * (data[0] + data[2]), 0.5 * (data[1] + data[3])))
                addPoint(subPath, doubleArrayOf(0.5 * (data[2] + data[4]), 0.5 * (data[3] + data[5])))
                prevCtrlPoint = doubleArrayOf(data[4] - data[2], data[5] - data[3])
            }

            "S" -> {
                if (basePoint != null && previous != null && (previous.command == "C" || previous.command == "S")) {
                    addPoint(
                        subPath,
                        doubleArrayOf(
                            basePoint[0] + 0.5 * prevCtrlPoint[0],
                            basePoint[1] + 0.5 * prevCtrlPoint[1],
                        ),
                    )
                    ctrlPoint = doubleArrayOf(basePoint[0] + prevCtrlPoint[0], basePoint[1] + prevCtrlPoint[1])
                }
                if (ctrlPoint != null) {
                    addPoint(
                        subPath,
                        doubleArrayOf(0.5 * (ctrlPoint[0] + data[0]), 0.5 * (ctrlPoint[1] + data[1])),
                    )
                }
                addPoint(subPath, doubleArrayOf(0.5 * (data[0] + data[2]), 0.5 * (data[1] + data[3])))
                prevCtrlPoint = doubleArrayOf(data[2] - data[0], data[3] - data[1])
            }

            "A" -> {
                if (basePoint != null) {
                    val curves = ArrayList(
                        a2c(
                            basePoint[0], basePoint[1], data[0], data[1], data[2],
                            data[3], data[4], data[5], data[6],
                        ),
                    )
                    while (true) {
                        val chunk = ArrayList<Double>()
                        var taken = 0
                        while (taken < 6 && curves.isNotEmpty()) {
                            chunk.add(curves.removeAt(0))
                            taken++
                        }
                        val cData = DoubleArray(chunk.size) { toAbsolute(chunk[it], it) }
                        if (cData.isEmpty()) break
                        basePoint?.let {
                            addPoint(subPath, doubleArrayOf(0.5 * (it[0] + cData[0]), 0.5 * (it[1] + cData[1])))
                        }
                        addPoint(subPath, doubleArrayOf(0.5 * (cData[0] + cData[2]), 0.5 * (cData[1] + cData[3])))
                        addPoint(subPath, doubleArrayOf(0.5 * (cData[2] + cData[4]), 0.5 * (cData[3] + cData[5])))
                        if (curves.isNotEmpty()) {
                            basePoint = doubleArrayOf(cData[cData.size - 2], cData[cData.size - 1])
                            addPoint(subPath, basePoint!!)
                        }
                    }
                }
            }
        }

        if (data.size >= 2) {
            addPoint(subPath, doubleArrayOf(data[data.size - 2], data[data.size - 1]))
        }
    }

    return points
}

private fun convexHull(points: Polygon): Polygon {
    points.list.sortWith { a, b ->
        val d = if (a[0] == b[0]) a[1] - b[1] else a[0] - b[0]
        if (d < 0) -1 else if (d > 0) 1 else 0
    }

    val lower = ArrayList<DoubleArray>()
    var minY = 0
    var bottom = 0
    for (i in points.list.indices) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], points.list[i]) <= 0) {
            lower.removeAt(lower.size - 1)
        }
        if (points.list[i][1] < points.list[minY][1]) {
            minY = i
            bottom = lower.size
        }
        lower.add(points.list[i])
    }

    val upper = ArrayList<DoubleArray>()
    var maxY = points.list.size - 1
    var top = 0
    for (i in points.list.indices.reversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], points.list[i]) <= 0) {
            upper.removeAt(upper.size - 1)
        }
        if (points.list[i][1] > points.list[maxY][1]) {
            maxY = i
            top = upper.size
        }
        upper.add(points.list[i])
    }

    if (upper.isNotEmpty()) upper.removeAt(upper.size - 1)
    if (lower.isNotEmpty()) lower.removeAt(lower.size - 1)

    val result = Polygon()
    result.list.addAll(lower)
    result.list.addAll(upper)
    result.minX = 0
    result.maxX = lower.size
    result.minY = bottom
    result.maxY = if (result.list.isNotEmpty()) (lower.size + top) % result.list.size else 0
    return result
}

/** Arc -> cubic bezier conversion (Raphael's `a2c`). */
private fun a2c(
    x1In: Double,
    y1In: Double,
    rxIn: Double,
    ryIn: Double,
    angle: Double,
    largeArcFlag: Double,
    sweepFlag: Double,
    x2In: Double,
    y2In: Double,
    recursive: DoubleArray? = null,
): List<Double> {
    val degrees120 = (PI * 120) / 180
    val rad = (PI / 180) * angle
    var res: List<Double> = emptyList()

    var x1 = x1In
    var y1 = y1In
    var x2 = x2In
    var y2 = y2In
    var rx = rxIn
    var ry = ryIn

    fun rotateX(x: Double, y: Double, r: Double): Double = x * cos(r) - y * sin(r)
    fun rotateY(x: Double, y: Double, r: Double): Double = x * sin(r) + y * cos(r)

    var f1: Double
    var f2: Double
    val cx: Double
    val cy: Double

    if (recursive == null) {
        // NOTE: the reference implementation reuses the already-rotated x1/x2 here; reproduced
        // verbatim so the output matches.
        x1 = rotateX(x1, y1, -rad)
        y1 = rotateY(x1, y1, -rad)
        x2 = rotateX(x2, y2, -rad)
        y2 = rotateY(x2, y2, -rad)
        val x = (x1 - x2) / 2
        val y = (y1 - y2) / 2
        var h = (x * x) / (rx * rx) + (y * y) / (ry * ry)
        if (h > 1) {
            h = sqrt(h)
            rx *= h
            ry *= h
        }
        val rx2 = rx * rx
        val ry2 = ry * ry
        val k = (if (largeArcFlag == sweepFlag) -1 else 1) *
            sqrt(abs((rx2 * ry2 - rx2 * y * y - ry2 * x * x) / (rx2 * y * y + ry2 * x * x)))
        cx = (k * rx * y) / ry + (x1 + x2) / 2
        cy = (k * -ry * x) / rx + (y1 + y2) / 2
        f1 = asin(clamp(jsToFixed((y1 - cy) / ry, 9)))
        f2 = asin(clamp(jsToFixed((y2 - cy) / ry, 9)))
        f1 = if (x1 < cx) PI - f1 else f1
        f2 = if (x2 < cx) PI - f2 else f2
        if (f1 < 0) f1 = PI * 2 + f1
        if (f2 < 0) f2 = PI * 2 + f2
        if (sweepFlag != 0.0 && f1 > f2) f1 -= PI * 2
        if (sweepFlag == 0.0 && f2 > f1) f2 -= PI * 2
    } else {
        f1 = recursive[0]
        f2 = recursive[1]
        cx = recursive[2]
        cy = recursive[3]
    }

    var df = f2 - f1
    if (abs(df) > degrees120) {
        val f2old = f2
        val x2old = x2
        val y2old = y2
        f2 = f1 + degrees120 * (if (sweepFlag != 0.0 && f2 > f1) 1 else -1)
        x2 = cx + rx * cos(f2)
        y2 = cy + ry * sin(f2)
        res = a2c(x2, y2, rx, ry, angle, 0.0, sweepFlag, x2old, y2old, doubleArrayOf(f2, f2old, cx, cy))
    }

    df = f2 - f1
    val c1 = cos(f1)
    val s1 = sin(f1)
    val c2 = cos(f2)
    val s2 = sin(f2)
    val t = tan(df / 4)
    val hx = (4.0 / 3) * rx * t
    val hy = (4.0 / 3) * ry * t
    val m = listOf(
        -hx * s1,
        hy * c1,
        x2 + hx * s2 - x1,
        y2 - hy * c2 - y1,
        x2 - x1,
        y2 - y1,
    )
    if (recursive != null) return m + res

    val combined = m + res
    val rotated = ArrayList<Double>(combined.size)
    for (i in combined.indices) {
        rotated.add(
            if (i % 2 == 1) {
                rotateY(combined[i - 1], combined[i], rad)
            } else {
                rotateX(combined[i], combined[i + 1], rad)
            },
        )
    }
    return rotated
}

private fun clamp(value: Double): Double = maxOf(-1.0, min(1.0, value))
