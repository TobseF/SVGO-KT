package io.github.tobsef.svgo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the full default preset and the public API.
 *
 * Expected outputs were produced by the reference JavaScript SVGO 4.1.0.
 */
class OptimizeTest {

    @Test
    fun version() {
        assertEquals("4.1.0", VERSION)
    }

    @Test
    fun defaultPresetSnapshot() {
        val svg = """
            <?xml version="1.0" encoding="utf-8"?>
            <svg viewBox="0 0 120 120">
              <desc>
                Created with love
              </desc>
              <circle fill="#ff0000" cx="60" cy="60" r="50"/>
            </svg>
        """
        val result = optimize(
            svg,
            Config(
                plugins = listOf(PluginConfig("preset-default")),
                js2svg = StringifyOptions(pretty = true, indent = 2),
            ),
        )
        assertEquals(
            "<svg viewBox=\"0 0 120 120\">\n" +
                "  <circle cx=\"60\" cy=\"60\" r=\"50\" fill=\"red\"/>\n" +
                "</svg>\n",
            result.data,
        )
    }

    @Test
    fun presetOverrides() {
        val svg = """
            <?xml version="1.0" encoding="utf-8"?>
            <svg viewBox="0 0 120 120">
              <desc>
                Not standard description
              </desc>
              <circle fill="#ff0000" cx="60" cy="60" r="50"/>
            </svg>
        """
        val result = optimize(
            svg,
            Config(
                plugins = listOf(
                    PluginConfig(
                        "preset-default",
                        Params.of(
                            "overrides" to mapOf(
                                "removeXMLProcInst" to false,
                                "removeDesc" to mapOf("removeAny" to true),
                            ),
                        ),
                    ),
                ),
                js2svg = StringifyOptions(pretty = true, indent = 2),
            ),
        )
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<svg viewBox=\"0 0 120 120\">\n" +
                "  <circle cx=\"60\" cy=\"60\" r=\"50\" fill=\"red\"/>\n" +
                "</svg>\n",
            result.data,
        )
    }

    /** (input, expected) pairs captured from reference SVGO 4.1.0 with default config. */
    private val snapshots = listOf(
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"1\" y=\"2\" width=\"3\" height=\"4\"/></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M1 2h3v4H1z\"/></svg>",
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><g><g><path d=\"M10 10 L 20 20 L 30 10\"/></g></g></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"m10 10 10 10 10-10\"/></svg>",
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"10\" cy=\"10\" r=\"5\" fill=\"#FF0000\"/></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"10\" cy=\"10\" r=\"5\" fill=\"red\"/></svg>",
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><ellipse cx=\"10\" cy=\"10\" rx=\"5\" ry=\"5\"/></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"10\" cy=\"10\" r=\"5\"/></svg>",
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><!-- c --><metadata>x</metadata>" +
            "<rect width=\"10\" height=\"10\"/></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0h10v10H0z\"/></svg>",
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">" +
            "<path d=\"M 10.0000 20.5000 C 30 40 50 60 70 80\" transform=\"translate(10,10)\"/></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">" +
            "<path d=\"M20 30.5C40 50 60 70 80 90\"/></svg>",
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><polygon points=\"10,10 20,10 20,20 10,20\"/></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M10 10h10v10H10z\"/></svg>",
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><g transform=\"scale(2)\">" +
            "<path d=\"M0 0 L10 10\" transform=\"rotate(45)\"/></g></svg>" to
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0v28.284\"/></svg>",
    )

    @Test
    fun defaultPresetPipeline() {
        for ((input, expected) in snapshots) {
            assertEquals(expected, optimize(input).data, "input: $input")
        }
    }

    @Test
    fun multipassReducesOrStops() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g><g>" +
            "<rect width=\"10\" height=\"10\"/></g></g></svg>"
        val once = optimize(svg).data
        val multi = optimize(svg, Config(multipass = true)).data
        assertTrue(multi.length <= once.length)
    }

    @Test
    fun datauriBase64() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"
        val result = optimize(svg, Config(datauri = "base64"))
        assertTrue(result.data.startsWith("data:image/svg+xml;base64,"))
        assertEquals(
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0h10v10H0z\"/></svg>",
            decodeSvgDatauri(result.data),
        )
    }

    @Test
    fun emptyPluginListRoundtrips() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"
        assertEquals(svg, optimize(svg, Config(plugins = emptyList())).data)
    }

    /**
     * Plugins remove attributes while walking them. `entries.toList()` keeps live entry views that
     * throw `ConcurrentModificationException` on Kotlin/JS and Kotlin/Native once the map changes,
     * so this has to be exercised on a non-JVM target -- the JVM tolerates the unsafe pattern.
     */
    @Test
    fun removesAttributesWhileIteratingThem() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 10 10">
              <title>icon</title>
              <rect x="0" y="0" width="10" height="10" fill="#FF0000" stroke="none" opacity="1"/>
            </svg>"""
        assertEquals(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 10 10\">" +
                "<title>icon</title><path fill=\"red\" d=\"M0 0h10v10H0z\"/></svg>",
            optimize(svg, Config(multipass = true)).data,
        )
    }

    @Test
    fun everyBuiltinPluginIsRegistered() {
        assertEquals(53, ALL_PLUGIN_NAMES.size)
        for (name in PRESET_DEFAULT_PLUGIN_NAMES) {
            assertTrue(getPlugin(name) != null, "missing plugin $name")
        }
        assertTrue(getPlugin("removeScriptElement") != null, "alias removeScriptElement")
        assertEquals(54, builtinPlugins().size)
    }
}
