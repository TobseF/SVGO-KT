package io.github.tobsef.svgo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Transform math. Port of `plugins/_transforms.js`. */
public class Transform(public var name: String, public var data: MutableList<Double>)

/** Rounding/serialization options shared by the transform helpers. */
public class TransformParams(
    public var floatPrecision: Int = 3,
    public var transformPrecision: Int = 5,
    public var degPrecision: Int? = null,
    public val leadingZero: Boolean = true,
    public val negativeExtraSpace: Boolean = false,
) {
    internal val outData: OutDataParams
        get() = OutDataParams(leadingZero = leadingZero, negativeExtraSpace = negativeExtraSpace)

    internal fun copy(): TransformParams =
        TransformParams(floatPrecision, transformPrecision, degPrecision, leadingZero, negativeExtraSpace)
}

private val TRANSFORM_TYPES = setOf("matrix", "rotate", "scale", "skewX", "skewY", "translate")

private val REG_TRANSFORM_SPLIT =
    Regex("""\s*(matrix|translate|scale|rotate|skewX|skewY)\s*\(\s*(.+?)\s*\)[\s,]*""")
private val REG_NUMERIC_VALUES = Regex("""[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")

/** `String.prototype.split(regexp)` semantics: capture groups are interleaved with the parts. */
private fun splitWithGroups(regex: Regex, input: String): List<String> {
    val result = ArrayList<String>()
    var last = 0
    for (match in regex.findAll(input)) {
        result.add(input.substring(last, match.range.first))
        for (i in 1 until match.groupValues.size) result.add(match.groupValues[i])
        last = match.range.last + 1
    }
    result.add(input.substring(last))
    return result
}

public fun transform2js(transformString: String): MutableList<Transform> {
    val transforms = ArrayList<Transform>()
    var current: Transform? = null
    for (item in splitWithGroups(REG_TRANSFORM_SPLIT, transformString)) {
        if (item.isEmpty()) continue
        if (item in TRANSFORM_TYPES) {
            current = Transform(item, ArrayList())
            transforms.add(current)
        } else {
            for (match in REG_NUMERIC_VALUES.findAll(item)) {
                current?.data?.add(match.value.toDouble())
            }
        }
    }
    if (current == null || current.data.isEmpty()) return ArrayList()
    return transforms
}

public fun transformsMultiply(transforms: List<Transform>): Transform {
    val matrixData = transforms.map { if (it.name == "matrix") it.data else transformToMatrix(it) }
    val data: MutableList<Double> = if (matrixData.isNotEmpty()) {
        matrixData.reduce { a, b -> multiplyTransformMatrices(a, b) }.toMutableList()
    } else {
        ArrayList()
    }
    return Transform("matrix", data)
}

private fun rad(deg: Double) = (deg * PI) / 180

private fun deg(rad: Double) = (rad * 180) / PI

private fun cosDeg(deg: Double) = cos(rad(deg))

private fun sinDeg(deg: Double) = sin(rad(deg))

private fun tanDeg(deg: Double) = tan(rad(deg))

private fun clampUnit(value: Double) = maxOf(-1.0, min(1.0, value))

private fun decomposeQrab(matrix: Transform): List<Transform>? {
    val (a, b, c, d, e, f) = matrix.data
    val delta = a * d - b * c
    if (delta == 0.0) return null
    val r = hypot(a, b)
    if (r == 0.0) return null
    val decomposition = ArrayList<Transform>()
    val cosRot = a / r
    if (e != 0.0 || f != 0.0) decomposition.add(Transform("translate", mutableListOf(e, f)))
    if (cosRot != 1.0) {
        val rot = acos(clampUnit(cosRot))
        decomposition.add(Transform("rotate", mutableListOf(deg(if (b < 0) -rot else rot), 0.0, 0.0)))
    }
    val sx = r
    val sy = delta / sx
    if (sx != 1.0 || sy != 1.0) decomposition.add(Transform("scale", mutableListOf(sx, sy)))
    val acPlusBd = a * c + b * d
    if (acPlusBd != 0.0) {
        decomposition.add(Transform("skewX", mutableListOf(deg(atan(acPlusBd / (a * a + b * b))))))
    }
    return decomposition
}

private fun decomposeQrcd(matrix: Transform): List<Transform>? {
    val (a, b, c, d, e, f) = matrix.data
    val delta = a * d - b * c
    if (delta == 0.0) return null
    val s = hypot(c, d)
    if (s == 0.0) return null
    val decomposition = ArrayList<Transform>()
    if (e != 0.0 || f != 0.0) decomposition.add(Transform("translate", mutableListOf(e, f)))
    val rot = PI / 2 - (if (d < 0) -1 else 1) * acos(clampUnit(-c / s))
    decomposition.add(Transform("rotate", mutableListOf(deg(rot), 0.0, 0.0)))
    val sx = delta / s
    val sy = s
    if (sx != 1.0 || sy != 1.0) decomposition.add(Transform("scale", mutableListOf(sx, sy)))
    val acPlusBd = a * c + b * d
    if (acPlusBd != 0.0) {
        decomposition.add(Transform("skewY", mutableListOf(deg(atan(acPlusBd / (c * c + d * d))))))
    }
    return decomposition
}

private operator fun List<Double>.component6(): Double = this[5]

private fun getDecompositions(matrix: Transform): List<List<Transform>> {
    val result = ArrayList<List<Transform>>()
    decomposeQrab(matrix)?.let { result.add(it) }
    decomposeQrcd(matrix)?.let { result.add(it) }
    return result
}

private fun mergeTranslateAndRotate(tx: Double, ty: Double, a: Double): Transform {
    val rot = rad(a)
    val d = 1 - cos(rot)
    val e = sin(rot)
    val cy = (d * ty + e * tx) / (d * d + e * e)
    val cx = (tx - e * cy) / d
    return Transform("rotate", mutableListOf(a, cx, cy))
}

private fun isIdentityTransform(t: Transform): Boolean = when (t.name) {
    "rotate", "skewX", "skewY" -> t.data[0] == 0.0
    "scale" -> t.data[0] == 1.0 && t.data[1] == 1.0
    "translate" -> t.data[0] == 0.0 && t.data[1] == 0.0
    else -> false
}

private fun createScaleTransform(data: List<Double>): Transform {
    val keep = if ((data.size > 1 && data[0] == data[1]) || data.size == 1) 1 else 2
    return Transform("scale", data.subList(0, min(keep, data.size)).toMutableList())
}

private fun optimizeDecomposition(
    roundedTransforms: List<Transform>,
    rawTransforms: List<Transform>,
): MutableList<Transform> {
    val optimized = ArrayList<Transform>()
    var index = 0
    while (index < roundedTransforms.size) {
        val rounded = roundedTransforms[index]
        if (isIdentityTransform(rounded)) {
            index++
            continue
        }
        val data = rounded.data
        when (rounded.name) {
            "rotate" -> {
                if (data[0] == 180.0 || data[0] == -180.0) {
                    val next = roundedTransforms.getOrNull(index + 1)
                    if (next != null && next.name == "scale") {
                        optimized.add(createScaleTransform(next.data.map { -it }))
                        index++
                    } else {
                        optimized.add(Transform("scale", mutableListOf(-1.0)))
                    }
                    index++
                    continue
                }
                val keep = if (data.size > 1 && (data[1] != 0.0 || (data.size > 2 && data[2] != 0.0))) 3 else 1
                optimized.add(Transform("rotate", data.subList(0, min(keep, data.size)).toMutableList()))
            }

            "scale" -> optimized.add(createScaleTransform(data))

            "skewX", "skewY" -> optimized.add(Transform(rounded.name, mutableListOf(data[0])))

            "translate" -> {
                val next = roundedTransforms.getOrNull(index + 1)
                if (next != null && next.name == "rotate" &&
                    next.data[0] != 180.0 && next.data[0] != -180.0 && next.data[0] != 0.0 &&
                    next.data[1] == 0.0 && next.data[2] == 0.0
                ) {
                    val raw = rawTransforms[index].data
                    optimized.add(mergeTranslateAndRotate(raw[0], raw[1], rawTransforms[index + 1].data[0]))
                    index += 2
                    continue
                }
                val keep = if (data.size > 1 && data[1] != 0.0) 2 else 1
                optimized.add(Transform("translate", data.subList(0, min(keep, data.size)).toMutableList()))
            }
        }
        index++
    }
    return if (optimized.isNotEmpty()) optimized else mutableListOf(Transform("scale", mutableListOf(1.0)))
}

public fun matrixToTransform(origMatrix: Transform, params: TransformParams): MutableList<Transform> {
    var shortest: MutableList<Transform>? = null
    var shortestLength = Int.MAX_VALUE
    for (decomposition in getDecompositions(origMatrix)) {
        val rounded = decomposition.map { roundTransform(Transform(it.name, ArrayList(it.data)), params) }
        val optimized = optimizeDecomposition(rounded, decomposition)
        // js2transform rounds the data in place; run it on `optimized` itself so the chosen result
        // keeps those rounded values (matches upstream).
        val length = js2transform(optimized, params).length
        if (length < shortestLength) {
            shortest = optimized
            shortestLength = length
        }
    }
    return shortest ?: mutableListOf(origMatrix)
}

public fun transformToMatrix(transform: Transform): MutableList<Double> {
    if (transform.name == "matrix") return transform.data
    val data = transform.data
    return when (transform.name) {
        "translate" -> mutableListOf(1.0, 0.0, 0.0, 1.0, data[0], if (data.size > 1) data[1] else 0.0)
        "scale" -> mutableListOf(data[0], 0.0, 0.0, if (data.size > 1) data[1] else data[0], 0.0, 0.0)
        "rotate" -> {
            val cos = cosDeg(data[0])
            val sin = sinDeg(data[0])
            val cx = if (data.size > 1) data[1] else 0.0
            val cy = if (data.size > 2) data[2] else 0.0
            mutableListOf(cos, sin, -sin, cos, (1 - cos) * cx + sin * cy, (1 - cos) * cy - sin * cx)
        }
        "skewX" -> mutableListOf(1.0, 0.0, tanDeg(data[0]), 1.0, 0.0, 0.0)
        "skewY" -> mutableListOf(1.0, tanDeg(data[0]), 0.0, 1.0, 0.0, 0.0)
        else -> throw IllegalArgumentException("Unknown transform ${transform.name}")
    }
}

public fun transformArc(cursor: DoubleArray, arc: MutableList<Double>, transform: List<Double>): MutableList<Double> {
    val x = arc[5] - cursor[0]
    val y = arc[6] - cursor[1]
    var a = arc[0]
    var b = arc[1]
    val rot = (arc[2] * PI) / 180
    val cos = cos(rot)
    val sin = sin(rot)
    if (a > 0 && b > 0) {
        var h = (x * cos + y * sin).pow(2) / (4 * a * a) + (y * cos - x * sin).pow(2) / (4 * b * b)
        if (h > 1) {
            h = sqrt(h)
            a *= h
            b *= h
        }
    }
    val ellipse = listOf(a * cos, a * sin, -b * sin, b * cos, 0.0, 0.0)
    val m = multiplyTransformMatrices(transform, ellipse)
    val lastCol = m[2] * m[2] + m[3] * m[3]
    val squareSum = m[0] * m[0] + m[1] * m[1] + lastCol
    val root = hypot(m[0] - m[3], m[1] + m[2]) * hypot(m[0] + m[3], m[1] - m[2])
    if (root == 0.0) {
        arc[0] = sqrt(squareSum / 2)
        arc[1] = arc[0]
        arc[2] = 0.0
    } else {
        val majorSqr = (squareSum + root) / 2
        val minorSqr = (squareSum - root) / 2
        val major = abs(majorSqr - lastCol) > 1e-6
        val subValue = (if (major) majorSqr else minorSqr) - lastCol
        val rowsSum = m[0] * m[2] + m[1] * m[3]
        val term1 = m[0] * subValue + m[2] * rowsSum
        val term2 = m[1] * subValue + m[3] * rowsSum
        arc[0] = sqrt(majorSqr)
        arc[1] = sqrt(minorSqr)
        val condition = if (major) term2 < 0 else term1 > 0
        val value = clampUnit((if (major) term1 else term2) / hypot(term1, term2))
        arc[2] = ((if (condition) -1 else 1) * acos(value) * 180) / PI
    }
    if ((transform[0] < 0) != (transform[3] < 0)) arc[4] = 1 - arc[4]
    return arc
}

public fun multiplyTransformMatrices(a: List<Double>, b: List<Double>): MutableList<Double> = mutableListOf(
    a[0] * b[0] + a[2] * b[1],
    a[1] * b[0] + a[3] * b[1],
    a[0] * b[2] + a[2] * b[3],
    a[1] * b[2] + a[3] * b[3],
    a[0] * b[4] + a[2] * b[5] + a[4],
    a[1] * b[4] + a[3] * b[5] + a[5],
)

public fun roundTransform(transform: Transform, params: TransformParams): Transform {
    val data = transform.data
    when (transform.name) {
        "translate" -> transform.data = floatRound(data, params)
        "rotate" -> transform.data = (degRound(data.subList(0, 1), params) + floatRound(data.subList(1, data.size), params)).toMutableList()
        "skewX", "skewY" -> transform.data = degRound(data, params)
        "scale" -> transform.data = transformRound(data, params)
        "matrix" -> transform.data = (transformRound(data.subList(0, min(4, data.size)), params) + floatRound(data.subList(min(4, data.size), data.size), params)).toMutableList()
    }
    return transform
}

private fun degRound(data: List<Double>, params: TransformParams): MutableList<Double> {
    val degPrecision = params.degPrecision
    if (degPrecision != null && degPrecision >= 1 && params.floatPrecision < 20) {
        return smartRound(degPrecision, data)
    }
    return roundAll(data)
}

private fun floatRound(data: List<Double>, params: TransformParams): MutableList<Double> {
    if (params.floatPrecision >= 1 && params.floatPrecision < 20) return smartRound(params.floatPrecision, data)
    return roundAll(data)
}

private fun transformRound(data: List<Double>, params: TransformParams): MutableList<Double> {
    if (params.transformPrecision >= 1 && params.floatPrecision < 20) {
        return smartRound(params.transformPrecision, data)
    }
    return roundAll(data)
}

private fun roundAll(data: List<Double>): MutableList<Double> = data.mapTo(ArrayList()) { jsRound(it) }

private fun smartRound(precision: Int, data: List<Double>): MutableList<Double> {
    val tolerance = jsToFixed(0.1.pow(precision), precision)
    val result = ArrayList(data)
    for (i in result.indices) {
        if (toFixed(result[i], precision) != result[i]) {
            val rounded = jsToFixed(result[i], precision - 1)
            result[i] = if (jsToFixed(abs(rounded - result[i]), precision + 1) >= tolerance) {
                jsToFixed(result[i], precision)
            } else {
                rounded
            }
        }
    }
    return result
}

public fun js2transform(transformJs: List<Transform>, params: TransformParams): String {
    val parts = StringBuilder()
    for (transform in transformJs) {
        roundTransform(transform, params)
        parts.append(transform.name).append('(')
            .append(cleanupOutData(transform.data, params.outData)).append(')')
    }
    return parts.toString()
}
