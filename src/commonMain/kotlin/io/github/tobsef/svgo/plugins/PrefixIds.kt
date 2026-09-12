package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Cdata
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.PluginInfo
import io.github.tobsef.svgo.Text
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.css.AtRule
import io.github.tobsef.svgo.css.CssNode
import io.github.tobsef.svgo.css.DeclarationNode
import io.github.tobsef.svgo.css.FunctionBlock
import io.github.tobsef.svgo.css.HashToken
import io.github.tobsef.svgo.css.IdentToken
import io.github.tobsef.svgo.css.LiteralToken
import io.github.tobsef.svgo.css.QualifiedRule
import io.github.tobsef.svgo.css.UrlToken
import io.github.tobsef.svgo.css.parseDeclarationList
import io.github.tobsef.svgo.css.parseRuleList
import io.github.tobsef.svgo.css.parseStylesheet
import io.github.tobsef.svgo.css.serialize
import io.github.tobsef.svgo.referencesProps

/** Produces the prefix for an id/class; used with the `prefix` parameter of [prefixIds]. */
public fun interface PrefixGenerator {
    public fun generate(node: Element, info: PluginInfo): String
}

private val REG_BASENAME = Regex("""[/\\]?([^/\\]+)$""")
private val REG_ESCAPE_IDENT = Regex("""[. ]""")
private val REG_SPLIT_WS = Regex("""\s+""")
private val REG_SPLIT_BEGIN = Regex("""\s*;\s+""")

// JS: /\burl\((["'])?(#.+?)\1\)/gi
private val REG_URL = Regex("""\burl\((?:"(#.+?)"|'(#.+?)'|(#.+?))\)""", RegexOption.IGNORE_CASE)

private fun getBasename(path: String): String = REG_BASENAME.find(path)?.groupValues?.get(1) ?: ""

private fun escapeIdentifier(value: String): String = REG_ESCAPE_IDENT.replace(value, "_")

private fun prefixId(generate: (String) -> String, body: String): String {
    val prefix = generate(body)
    if (body.startsWith(prefix)) return body
    return prefix + body
}

private fun prefixReference(generate: (String) -> String, reference: String): String? =
    if (reference.startsWith("#")) "#" + prefixId(generate, reference.substring(1)) else null

/** prefix IDs */
public val prefixIds: PluginDefinition = PluginDefinition(
    "prefixIds",
    "prefix IDs",
) { _, params, info ->
    val delim = params.string("delim", "__")
    val prefixParam = if (params.has("prefix")) params.raw("prefix") else null
    val prefixIdsEnabled = params.bool("prefixIds", true)
    val prefixClassNames = params.bool("prefixClassNames", true)
    val prefixMap = HashMap<String, String>()

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                val generate: (String) -> String = { body ->
                    when {
                        prefixParam is PrefixGenerator -> prefixMap.getOrPut(body) {
                            prefixParam.generate(node, info) + delim
                        }
                        prefixParam is String -> prefixParam + delim
                        prefixParam == false -> ""
                        else -> {
                            val path = info.path
                            if (!path.isNullOrEmpty()) escapeIdentifier(getBasename(path)) + delim else "prefix$delim"
                        }
                    }
                }

                var failed = false
                if (node.name == "style" && node.children.isNotEmpty()) {
                    for (child in node.children) {
                        val value = when (child) {
                            is Text -> child.value
                            is Cdata -> child.value
                            else -> null
                        } ?: continue
                        try {
                            val prefixed = prefixCss(value, generate, prefixIdsEnabled, prefixClassNames)
                            when (child) {
                                is Text -> child.value = prefixed
                                is Cdata -> child.value = prefixed
                                else -> {}
                            }
                        } catch (_: Exception) {
                            failed = true
                        }
                    }
                }

                if (!failed) {
                    val id = node.attributes["id"]
                    if (prefixIdsEnabled && !id.isNullOrEmpty()) {
                        node.attributes["id"] = prefixId(generate, id)
                    }

                    val classAttr = node.attributes["class"]
                    if (prefixClassNames && !classAttr.isNullOrEmpty()) {
                        node.attributes["class"] =
                            classAttr.split(REG_SPLIT_WS).joinToString(" ") { prefixId(generate, it) }
                    }

                    for (attr in listOf("href", "xlink:href")) {
                        val value = node.attributes[attr]
                        if (!value.isNullOrEmpty()) {
                            prefixReference(generate, value)?.let { node.attributes[attr] = it }
                        }
                    }

                    for (attr in referencesProps) {
                        val value = node.attributes[attr]
                        if (!value.isNullOrEmpty()) {
                            node.attributes[attr] = REG_URL.replace(value) { match ->
                                val url = match.groupValues[1].ifEmpty {
                                    match.groupValues[2].ifEmpty { match.groupValues[3] }
                                }
                                val prefixed = prefixReference(generate, url)
                                if (prefixed == null) match.value else "url($prefixed)"
                            }
                        }
                    }

                    for (attr in listOf("begin", "end")) {
                        val value = node.attributes[attr]
                        if (!value.isNullOrEmpty()) {
                            node.attributes[attr] = value.split(REG_SPLIT_BEGIN).joinToString("; ") { piece ->
                                if (piece.endsWith(".end") || piece.endsWith(".start")) {
                                    val segments = piece.split(".")
                                    prefixId(generate, segments[0]) + "." + segments[1]
                                } else {
                                    piece
                                }
                            }
                        }
                    }
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// <style> CSS prefixing (token-based, minified output ~ csstree.generate)
// ---------------------------------------------------------------------------

private fun prefixCss(
    cssText: String,
    generate: (String) -> String,
    prefixIdsEnabled: Boolean,
    prefixClassNames: Boolean,
): String = parseStylesheet(cssText).joinToString("") {
    renderPrefixedNode(it, generate, prefixIdsEnabled, prefixClassNames)
}

private val REG_WS_RUN = Regex("""\s+""")
private val REG_AROUND_COMBINATOR = Regex("""\s*([>+~])\s*""")

private fun minifyWs(text: String): String = REG_WS_RUN.replace(text, " ")

private fun minifySelector(text: String): String {
    var result = REG_WS_RUN.replace(text.trim(), " ")
    result = REG_AROUND_COMBINATOR.replace(result) { it.groupValues[1] }
    return result
}

private fun renderPrefixedNode(
    node: CssNode,
    generate: (String) -> String,
    prefixIdsEnabled: Boolean,
    prefixClassNames: Boolean,
): String = when (node) {
    is QualifiedRule -> {
        val prelude = prefixSelectorTokens(node.prelude, generate, prefixIdsEnabled, prefixClassNames)
        val content = prefixBlock(node.content ?: emptyList(), generate)
        "$prelude{$content}"
    }

    is AtRule -> {
        val preludeText = minifyWs(serialize(node.prelude)).trim()
        val header = "@" + node.atKeyword + (if (preludeText.isNotEmpty()) " $preludeText" else "")
        val content = node.content
        if (content == null) {
            "$header;"
        } else {
            val inner = parseRuleList(content)
            // keyframe/media blocks: recurse; declaration-only at-rules (@font-face) do not
            val body = if (inner.any { it is QualifiedRule || it is AtRule }) {
                inner.joinToString("") { renderPrefixedNode(it, generate, prefixIdsEnabled, prefixClassNames) }
            } else {
                prefixBlock(content, generate)
            }
            "$header{$body}"
        }
    }

    else -> node.serialize()
}

private fun prefixSelectorTokens(
    tokens: List<CssNode>,
    generate: (String) -> String,
    prefixIdsEnabled: Boolean,
    prefixClassNames: Boolean,
): String {
    val out = StringBuilder()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        when {
            token is HashToken && prefixIdsEnabled -> out.append("#").append(prefixId(generate, token.value))

            token is LiteralToken && token.value == "." && prefixClassNames &&
                i + 1 < tokens.size && tokens[i + 1] is IdentToken -> {
                out.append(".").append(prefixId(generate, (tokens[i + 1] as IdentToken).value))
                i += 2
                continue
            }

            token is UrlToken -> {
                val prefixed = prefixReference(generate, token.value)
                out.append("url(").append(prefixed ?: token.value).append(")")
            }

            else -> out.append(token.serialize())
        }
        i++
    }
    return minifySelector(out.toString())
}

private fun prefixBlock(contentTokens: List<CssNode>, generate: (String) -> String): String {
    val parts = ArrayList<String>()
    for (declaration in parseDeclarationList(contentTokens).filterIsInstance<DeclarationNode>()) {
        val value = prefixValueTokens(declaration.value, generate)
        val important = if (declaration.important) "!important" else ""
        val property = if (declaration.name.startsWith("--")) declaration.name else declaration.lowerName
        parts.add("$property:$value$important")
    }
    return parts.joinToString(";")
}

private fun unquote(value: String): String =
    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
        value.substring(1, value.length - 1)
    } else {
        value
    }

private fun prefixValueTokens(tokens: List<CssNode>, generate: (String) -> String): String {
    val out = StringBuilder()
    for (token in tokens) {
        when {
            token is UrlToken -> {
                val prefixed = prefixReference(generate, token.value)
                out.append("url(").append(prefixed ?: token.value).append(")")
            }

            token is FunctionBlock && token.lowerName == "url" -> {
                val inner = serialize(token.arguments).trim()
                val prefixed = prefixReference(generate, unquote(inner))
                if (prefixed != null) out.append("url($prefixed)") else out.append(token.serialize())
            }

            else -> out.append(token.serialize())
        }
    }
    return minifyWs(out.toString()).trim()
}
