package io.github.tobsef.svgo

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * JavaScript number semantics, reproduced exactly and without platform dependencies.
 *
 * SVGO's output is byte-compared against the reference implementation, so number formatting has to
 * match ECMA-262 `Number.prototype.toString` and `toFixed` precisely -- including the exponent
 * thresholds (`< 1e-6` / `>= 1e21`), the exponent spelling (`1e-7`, not `1e-07`) and `toFixed`'s
 * "round the exact binary value, ties away from zero" rule.
 *
 * Everything is derived from the *exact* decimal expansion of the IEEE-754 double, computed with a
 * small base-10^9 big-integer helper, so results are identical on every Kotlin target.
 */

// ---------------------------------------------------------------------------
// minimal unsigned big integer (base 1e9, little-endian limbs)
// ---------------------------------------------------------------------------

private const val LIMB_BASE = 1_000_000_000L

private class BigNat(value: Long) {
    var limbs: IntArray
    var size: Int

    init {
        var v = value
        val tmp = ArrayList<Int>(4)
        if (v == 0L) {
            tmp.add(0)
        } else {
            while (v > 0) {
                tmp.add((v % LIMB_BASE).toInt())
                v /= LIMB_BASE
            }
        }
        limbs = IntArray(tmp.size + 8) { if (it < tmp.size) tmp[it] else 0 }
        size = tmp.size
    }

    fun mulSmall(multiplier: Long) {
        var carry = 0L
        for (i in 0 until size) {
            val cur = limbs[i] * multiplier + carry
            limbs[i] = (cur % LIMB_BASE).toInt()
            carry = cur / LIMB_BASE
        }
        while (carry > 0) {
            ensure(size + 1)
            limbs[size] = (carry % LIMB_BASE).toInt()
            carry /= LIMB_BASE
            size++
        }
    }

    private fun ensure(capacity: Int) {
        if (capacity > limbs.size) {
            limbs = limbs.copyOf(maxOf(capacity, limbs.size * 2))
        }
    }

    fun digits(): String {
        val sb = StringBuilder()
        sb.append(limbs[size - 1].toString())
        for (i in size - 2 downTo 0) {
            val s = limbs[i].toString()
            repeat(9 - s.length) { sb.append('0') }
            sb.append(s)
        }
        return sb.toString()
    }
}

/**
 * Exact decimal expansion of a finite, non-zero, positive double:
 * `value == 0.<digits> * 10^pointExp`, with [digits] carrying no leading zero.
 */
private class ExactDecimal(val digits: String, val pointExp: Int)

private fun exactDecimal(value: Double): ExactDecimal {
    val bits = value.toRawBits()
    val biasedExp = ((bits ushr 52) and 0x7FF).toInt()
    val fraction = bits and 0x000F_FFFF_FFFF_FFFFL
    val mantissa: Long
    val exponent: Int
    if (biasedExp == 0) {
        mantissa = fraction
        exponent = -1074
    } else {
        mantissa = fraction or (1L shl 52)
        exponent = biasedExp - 1075
    }

    val nat = BigNat(mantissa)
    val shift: Int
    if (exponent >= 0) {
        // value == mantissa * 2^exponent
        var left = exponent
        while (left > 0) {
            val step = minOf(left, 29)
            nat.mulSmall(1L shl step)
            left -= step
        }
        shift = 0
    } else {
        // value == (mantissa * 5^k) * 10^-k
        var left = -exponent
        shift = left
        while (left > 0) {
            val step = minOf(left, 12)
            nat.mulSmall(POW5[step])
            left -= step
        }
    }
    val digits = nat.digits()
    return ExactDecimal(digits, digits.length - shift)
}

private val POW5 = longArrayOf(
    1L, 5L, 25L, 125L, 625L, 3125L, 15625L, 78125L, 390625L, 1953125L,
    9765625L, 48828125L, 244140625L,
)

/** Round the decimal string [digits] to [keep] significant digits; returns the new digit string
 * (possibly one longer, e.g. `"999" -> "100"` with the carry reported through [carriedOut]). */
private class RoundedDigits(val digits: String, val exponentShift: Int)

private fun roundDigits(digits: String, keep: Int, roundHalfEven: Boolean): RoundedDigits {
    if (keep >= digits.length) return RoundedDigits(digits, 0)
    val head = digits.substring(0, keep)
    val nextDigit = digits[keep]
    var roundUp = nextDigit > '5'
    if (nextDigit == '5') {
        var restNonZero = false
        for (i in keep + 1 until digits.length) {
            if (digits[i] != '0') {
                restNonZero = true
                break
            }
        }
        roundUp = if (restNonZero) {
            true
        } else if (roundHalfEven) {
            keep > 0 && ((head[keep - 1] - '0') % 2 == 1)
        } else {
            true
        }
    }
    if (!roundUp) return RoundedDigits(head, 0)
    val chars = head.toCharArray()
    var i = keep - 1
    while (i >= 0) {
        if (chars[i] == '9') {
            chars[i] = '0'
            i--
        } else {
            chars[i] = chars[i] + 1
            break
        }
    }
    // every digit carried out ("999" -> "100" with the decimal point moved one place right)
    return if (i < 0) RoundedDigits("1" + "0".repeat(keep - 1), 1)
    else RoundedDigits(chars.concatToString(), 0)
}

/** Build the plain `0.<digits>E<exp>` form and parse it back -- used for round-trip checks. */
private fun reconstruct(digits: String, pointExp: Int): Double =
    ("0." + digits + "E" + pointExp).toDouble()

private fun stripTrailingZeros(digits: String): String {
    var end = digits.length
    while (end > 1 && digits[end - 1] == '0') end--
    return digits.substring(0, end)
}

/** ECMA-262 `Number::toString` formatting from the shortest digits [s] and decimal position [n]. */
private fun formatEcma(s: String, n: Int): String {
    val k = s.length
    if (n in k..21) return s + "0".repeat(n - k)
    if (n in 1..21) return s.substring(0, n) + "." + s.substring(n)
    if (n in -5..0) return "0." + "0".repeat(-n) + s
    val e = n - 1
    val sign = if (e >= 0) "+" else "-"
    val mantissa = if (k == 1) s else s[0] + "." + s.substring(1)
    return mantissa + "e" + sign + abs(e).toString()
}

/**
 * Format a number exactly the way JavaScript's `Number.prototype.toString` does.
 */
public fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value == Double.POSITIVE_INFINITY) return "Infinity"
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
    if (value == 0.0) return "0"

    val negative = value < 0
    val magnitude = abs(value)
    val exact = exactDecimal(magnitude)

    // shortest round-tripping representation
    var digits = stripTrailingZeros(exact.digits)
    var pointExp = exact.pointExp
    for (keep in 1..digits.length) {
        var candidate = roundDigits(digits, keep, roundHalfEven = true)
        var candidateDigits = stripTrailingZeros(candidate.digits)
        var candidateExp = pointExp + candidate.exponentShift
        if (reconstruct(candidateDigits, candidateExp) == magnitude) {
            digits = candidateDigits
            pointExp = candidateExp
            break
        }
        candidate = roundDigits(digits, keep, roundHalfEven = false)
        candidateDigits = stripTrailingZeros(candidate.digits)
        candidateExp = pointExp + candidate.exponentShift
        if (reconstruct(candidateDigits, candidateExp) == magnitude) {
            digits = candidateDigits
            pointExp = candidateExp
            break
        }
    }

    val result = formatEcma(digits, pointExp)
    return if (negative) "-$result" else result
}

/**
 * Replicate `Number(x.toFixed(precision))`.
 *
 * `toFixed` rounds the *exact* binary value of the double with ties going away from zero -- which
 * is what makes `(1.005).toFixed(2) === "1.00"`.
 */
public fun jsToFixed(x: Double, precision: Int): Double {
    if (x.isNaN() || x.isInfinite()) return x
    if (x == 0.0) return x
    val negative = x < 0
    val magnitude = abs(x)
    val exact = exactDecimal(magnitude)
    val digits = exact.digits
    val idx = exact.pointExp + precision

    val value: Double = when {
        idx < 0 -> 0.0
        idx == 0 -> if (digits[0] >= '5') reconstruct("1", 1 - precision) else 0.0
        idx >= digits.length -> magnitude
        else -> {
            val rounded = roundDigits(digits, idx, roundHalfEven = false)
            reconstruct(stripTrailingZeros(rounded.digits), exact.pointExp + rounded.exponentShift)
        }
    }
    return if (negative) -value else value
}

/** JavaScript `Math.round`: round half *up* (toward +Infinity). */
public fun jsRound(x: Double): Double = floor(x + 0.5)

/** `Math.round(num * 10^precision) / 10^precision`, as used throughout SVGO. */
public fun toFixed(num: Double, precision: Int): Double {
    val pow = 10.0.pow(precision)
    return jsRound(num * pow) / pow
}

/** `0.5 -> ".5"`, `-0.5 -> "-.5"`. */
public fun removeLeadingZero(value: Double): String {
    val stringValue = jsNumberToString(value)
    if (value > 0 && value < 1 && stringValue.startsWith("0")) return stringValue.substring(1)
    if (value > -1 && value < 0 && stringValue.length > 1 && stringValue[1] == '0') {
        return stringValue[0] + stringValue.substring(2)
    }
    return stringValue
}

private val DECIMAL_NUMBER = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?$")

/**
 * Replicate JavaScript `Number(value)` for the cases SVGO relies on: `Number('')` and `Number(' ')`
 * are `0`, surrounding whitespace is ignored, anything not fully numeric (e.g. `'100%'`) is `NaN`.
 */
public fun jsNumber(value: String?): Double {
    if (value == null) return Double.NaN
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return 0.0
    if (!DECIMAL_NUMBER.matches(trimmed)) return Double.NaN
    return trimmed.toDoubleOrNull() ?: Double.NaN
}

private val FLOAT_PREFIX = Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")

/**
 * Replicate JavaScript `Number.parseFloat`: parse the longest valid numeric prefix, ignoring a
 * trailing incomplete exponent (e.g. `5e+` -> 5).
 */
public fun jsParseFloat(value: String): Double? {
    val match = FLOAT_PREFIX.find(value) ?: return null
    if (match.range.first != 0) return null
    return match.value.toDoubleOrNull()
}
