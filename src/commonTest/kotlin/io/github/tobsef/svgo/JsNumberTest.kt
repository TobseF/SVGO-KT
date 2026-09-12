package io.github.tobsef.svgo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ECMA-262 number formatting, the foundation of byte-identical SVGO output. */
class JsNumberTest {

    @Test
    fun matchesJavaScriptToString() {
        val cases = listOf(
            0.0 to "0",
            -0.0 to "0",
            1.0 to "1",
            -1.5 to "-1.5",
            0.1 to "0.1",
            0.3 to "0.3",
            (0.1 + 0.2) to "0.30000000000000004",
            100.0 to "100",
            1e20 to "100000000000000000000",
            1e21 to "1e+21",
            1.5e21 to "1.5e+21",
            1e-6 to "0.000001",
            1e-7 to "1e-7",
            5e-324 to "5e-324",
            1.7976931348623157e308 to "1.7976931348623157e+308",
            123456789012345680000.0 to "123456789012345680000",
            0.000001234 to "0.000001234",
            3.141592653589793 to "3.141592653589793",
            -0.5 to "-0.5",
            255.0 to "255",
            2.5e-10 to "2.5e-10",
        )
        for ((value, expected) in cases) {
            assertEquals(expected, jsNumberToString(value), "for $expected")
        }
        assertEquals("NaN", jsNumberToString(Double.NaN))
        assertEquals("Infinity", jsNumberToString(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", jsNumberToString(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun roundTripsEveryFormattedValue() {
        // a deterministic sweep over exponents and mantissas (kept small enough to stay well
        // inside the JS test runner's default timeout)
        var seed = 0x5DEECE66DL
        repeat(2000) {
            seed = (seed * 0x5DEECE66DL + 0xB) and ((1L shl 48) - 1)
            val bits = (seed shl 16) xor seed
            val value = Double.fromBits(bits)
            if (value.isNaN() || value.isInfinite()) return@repeat
            val text = jsNumberToString(value)
            assertEquals(value, text.toDouble(), "round trip of $text")
        }
    }

    @Test
    fun shortestRepresentation() {
        // 0.1 must not print as 0.1000000000000000055511151231257827
        assertTrue(jsNumberToString(0.1).length == 3)
        assertTrue(jsNumberToString(1.0 / 3.0) == "0.3333333333333333")
    }

    @Test
    fun toFixedRoundsTheExactBinaryValue() {
        // the classic: (1.005).toFixed(2) === "1.00" because 1.005 is really 1.00499999...
        assertEquals(1.0, jsToFixed(1.005, 2))
        assertEquals(1.01, jsToFixed(1.0051, 2))
        assertEquals(2.35, jsToFixed(2.345, 2))
        assertEquals(0.0, jsToFixed(0.0001, 2))
        assertEquals(-1.0, jsToFixed(-1.005, 2))
        assertEquals(-2.35, jsToFixed(-2.345, 2))
        assertEquals(0.1, jsToFixed(0.05, 1))
        assertEquals(123.0, jsToFixed(123.456, 0))
        assertEquals(123.456, jsToFixed(123.456, 6))
    }

    @Test
    fun mathRoundIsHalfUp() {
        assertEquals(1.0, jsRound(0.5))
        assertEquals(0.0, jsRound(-0.5))
        assertEquals(-1.0, jsRound(-1.5))
        assertEquals(3.0, jsRound(2.5))
    }

    @Test
    fun leadingZeroRemoval() {
        assertEquals(".5", removeLeadingZero(0.5))
        assertEquals("-.5", removeLeadingZero(-0.5))
        assertEquals("1.5", removeLeadingZero(1.5))
        assertEquals("0", removeLeadingZero(0.0))
        assertEquals("-1", removeLeadingZero(-1.0))
    }

    @Test
    fun numberCoercion() {
        assertEquals(0.0, jsNumber(""))
        assertEquals(0.0, jsNumber(" "))
        assertEquals(10.0, jsNumber(" 10 "))
        assertTrue(jsNumber("100%").isNaN())
        assertTrue(jsNumber(null).isNaN())
        assertEquals(1000.0, jsNumber("1e3"))
    }
}
