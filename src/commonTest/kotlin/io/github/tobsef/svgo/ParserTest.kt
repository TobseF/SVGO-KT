package io.github.tobsef.svgo

import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of `lib/parser.test.js` plus stringifier round-trips. */
class ParserTest {

    @Test
    fun textIsPreserved() {
        val input = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\">\n")
            append("<text x=\"10\" y=\"35\" xml:space=\"preserve\">\n")
            append("    <a href=\"x\">\n")
            append("this is a test\n")
            append("    </a>\n")
            append("</text>\n")
            append("<text x=\"10\" y=\"35\" xml:space=\"preserve\">\n")
            append("    <tspan>\n")
            append("this is a test\n")
            append("    </tspan>\n")
            append("</text>\n")
            append("</svg>")
        }
        val expected = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\"><text x=\"10\" y=\"35\" xml:space=\"preserve\">\n")
            append("    <a href=\"x\">\n")
            append("this is a test\n")
            append("    </a>\n")
            append("</text><text x=\"10\" y=\"35\" xml:space=\"preserve\">\n")
            append("    <tspan>\n")
            append("this is a test\n")
            append("    </tspan>\n")
            append("</text></svg>")
        }
        assertEquals(expected, stringifySvg(parseSvg(input)))
    }

    @Test
    fun entitiesAndCdataRoundtrip() {
        val svg = "<svg><style>.a{fill:red}</style><![CDATA[ raw ]]>text &amp; more</svg>"
        assertEquals(svg, stringifySvg(parseSvg(svg)))
    }

    @Test
    fun commentIsTrimmed() {
        assertEquals(
            "<svg><!--spaced--><g/></svg>",
            stringifySvg(parseSvg("<svg><!--  spaced  --><g/></svg>")),
        )
    }

    @Test
    fun doctypeIsPreserved() {
        val svg = "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" " +
            "\"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">" +
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
        assertEquals(svg, stringifySvg(parseSvg(svg)))
    }

    @Test
    fun selfClosingShortTags() {
        val svg = "<svg><rect width=\"10\" height=\"10\"/></svg>"
        assertEquals(svg, stringifySvg(parseSvg(svg)))
    }

    @Test
    fun internalEntitiesAreApplied() {
        val svg = "<!DOCTYPE svg [<!ENTITY hi \"hello\">]><svg><text>&hi;</text></svg>"
        val root = parseSvg(svg)
        val text = (root.children[1] as Element).children[0] as Element
        assertEquals("hello", (text.children[0] as Text).value)
    }
}
