package io.github.tobsef.svgo

import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of `lib/path.test.js`. */
class PathTest {

    private fun pd(vararg items: Pair<String, List<Double>>): List<PathItem> =
        items.map { PathItem(it.first, it.second.toMutableList()) }

    private fun List<PathItem>.render(): String =
        joinToString(",") { "${it.command}[${it.args.joinToString(" ") { arg -> jsNumberToString(arg) }}]" }

    private fun assertPath(expected: List<PathItem>, actual: List<PathItem>) =
        assertEquals(expected.render(), actual.render())

    @Test
    fun spacesBetweenCommands() {
        assertPath(
            pd("M" to listOf(0.0, 10.0), "L" to listOf(20.0, 30.0)),
            parsePathData("M0 10 L \n\r\t20 30"),
        )
    }

    @Test
    fun spacesAndCommasBetweenArguments() {
        assertPath(
            pd("M" to listOf(0.0, 10.0), "L" to listOf(20.0, 30.0), "L" to listOf(40.0, 50.0)),
            parsePathData("M0 , 10 L 20 \n\r\t30,40,50"),
        )
    }

    @Test
    fun forbidCommasBeforeCommands() {
        assertPath(emptyList(), parsePathData(", M0 10"))
    }

    @Test
    fun forbidCommasBetweenCommands() {
        assertPath(pd("M" to listOf(0.0, 10.0)), parsePathData("M0,10 , L 20,30"))
    }

    @Test
    fun forbidCommasBetweenNameAndArgument() {
        assertPath(pd("M" to listOf(0.0, 10.0)), parsePathData("M0,10 L,20,30"))
    }

    @Test
    fun forbidMultipleCommas() {
        assertPath(emptyList(), parsePathData("M0 , , 10"))
    }

    @Test
    fun stopOnUnknownChar() {
        assertPath(pd("M" to listOf(0.0, 10.0)), parsePathData("M0 10 , L 20 #40"))
    }

    @Test
    fun stopWhenNotEnoughArguments() {
        assertPath(pd("M" to listOf(0.0, 10.0)), parsePathData("M0 10 L 20 L 30 40"))
    }

    @Test
    fun stopWhenMovetoIsNotFirst() {
        assertPath(emptyList(), parsePathData("L 10 20"))
        assertPath(emptyList(), parsePathData("10 20"))
    }

    @Test
    fun stopOnInvalidScientificNotation() {
        assertPath(pd("M" to listOf(0.0, 5.0)), parsePathData("M 0 5e++1 L 0 0"))
    }

    @Test
    fun stopOnInvalidNumbers() {
        assertPath(emptyList(), parsePathData("M ..."))
    }

    @Test
    fun arcs() {
        val result = parsePathData(
            """
              M600,350
              l 50,-25
              a25,25 -30 0,1 50,-25
              25,50 -30 0,1 50,-25
              25,75 -30 01.2,-25
              a25,100 -30 0150,-25
              l 50,-25
            """,
        )
        assertPath(
            pd(
                "M" to listOf(600.0, 350.0),
                "l" to listOf(50.0, -25.0),
                "a" to listOf(25.0, 25.0, -30.0, 0.0, 1.0, 50.0, -25.0),
                "a" to listOf(25.0, 50.0, -30.0, 0.0, 1.0, 50.0, -25.0),
                "a" to listOf(25.0, 75.0, -30.0, 0.0, 1.0, 0.2, -25.0),
                "a" to listOf(25.0, 100.0, -30.0, 0.0, 1.0, 50.0, -25.0),
                "l" to listOf(50.0, -25.0),
            ),
            result,
        )
    }

    @Test
    fun normalizeSignedArcRadii() {
        assertPath(
            pd(
                "M" to listOf(0.0, 0.0),
                "A" to listOf(10.0, 20.0, 0.0, 0.0, 1.0, 30.0, 40.0),
                "a" to listOf(50.0, 60.0, 0.0, 1.0, 0.0, 70.0, 80.0),
            ),
            parsePathData("M0 0A-10 +20 0 0 1 30 40a+50 -60 0 1 0 70 80"),
        )
    }

    @Test
    fun combineSameCommands() {
        assertEquals(
            "M0 0h10 20 30H40 50",
            stringifyPathData(
                pd(
                    "M" to listOf(0.0, 0.0), "h" to listOf(10.0), "h" to listOf(20.0),
                    "h" to listOf(30.0), "H" to listOf(40.0), "H" to listOf(50.0),
                ),
            ),
        )
    }

    @Test
    fun doNotCombineMoveto() {
        assertEquals(
            "M0 0M10 10m20 30m40 50",
            stringifyPathData(
                pd(
                    "M" to listOf(0.0, 0.0), "M" to listOf(10.0, 10.0),
                    "m" to listOf(20.0, 30.0), "m" to listOf(40.0, 50.0),
                ),
            ),
        )
    }

    @Test
    fun combineMovetoAndLineto() {
        assertEquals(
            "m0 0 10 10M0 0l10 10M0 0 10 10",
            stringifyPathData(
                pd(
                    "M" to listOf(0.0, 0.0), "l" to listOf(10.0, 10.0),
                    "M" to listOf(0.0, 0.0), "l" to listOf(10.0, 10.0),
                    "M" to listOf(0.0, 0.0), "L" to listOf(10.0, 10.0),
                ),
            ),
        )
        assertEquals(
            "M0 0 10 10",
            stringifyPathData(pd("m" to listOf(0.0, 0.0), "L" to listOf(10.0, 10.0))),
        )
    }

    @Test
    fun avoidSpaceBeforeFirstNegativeDecimals() {
        assertEquals(
            "M0-1.2.3 4 5-.6 7 .8",
            stringifyPathData(
                pd(
                    "M" to listOf(0.0, -1.2), "L" to listOf(0.3, 4.0),
                    "L" to listOf(5.0, -0.6), "L" to listOf(7.0, 0.8),
                ),
            ),
        )
    }

    @Test
    fun spaceBeforeScientificNotation() {
        assertEquals(
            "M.1 1e-7 2 2",
            stringifyPathData(pd("M" to listOf(0.1, 1e-7), "L" to listOf(2.0, 2.0)), precision = 7),
        )
    }

    @Test
    fun configurePrecision() {
        val pathData = pd(
            "M" to listOf(0.0, -1.9876), "L" to listOf(0.3, 3.14159265),
            "L" to listOf(-0.3, -3.14159265), "L" to listOf(100.0, 200.0),
        )
        assertEquals("M0-1.988.3 3.142-.3-3.142 100 200", stringifyPathData(pathData, precision = 3))
        assertEquals("M0-2 0 3 0-3 100 200", stringifyPathData(pathData, precision = 0))
    }

    @Test
    fun disableSpaceAfterFlags() {
        val pathData = pd(
            "M" to listOf(0.0, 0.0),
            "A" to listOf(50.0, 50.0, 10.0, 1.0, 0.0, 0.2, 20.0),
            "a" to listOf(50.0, 50.0, 10.0, 1.0, 0.0, 0.2, 20.0),
            "a" to listOf(50.0, 50.0, 10.0, 1.0, 0.0, 0.2, 20.0),
        )
        assertEquals(
            "M0 0A50 50 10 1 0 .2 20a50 50 10 1 0 .2 20 50 50 10 1 0 .2 20",
            stringifyPathData(pathData, disableSpaceAfterFlags = false),
        )
        assertEquals(
            "M0 0A50 50 10 10.2 20a50 50 10 10.2 20 50 50 10 10.2 20",
            stringifyPathData(pathData, disableSpaceAfterFlags = true),
        )
    }
}
