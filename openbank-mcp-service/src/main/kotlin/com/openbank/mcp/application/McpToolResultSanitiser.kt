// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import java.text.Normalizer

/**
 * ADR-0383 D7 free-text sanitisation of MCP tool results (#16620).
 *
 * Applied at the same single seam as [McpPiiMasker] and [McpUntrustedData] (`McpToolRegistry.call`),
 * after masking and before wrapping. Masking redacts free text wholesale only where the FIELD NAME
 * says it is a name or narrative; a payment reference, a statement `detail`, a merchant category or
 * a statement label is passed through by the masker and is just as attacker-influenceable.
 *
 * **Deny-by-default over string values, not an allow-list of field names.** Every string in the
 * result goes through [sanitiseText]; the only exception is a URL-by-contract field ([URL_FIELDS]),
 * which is checked against the bank CDN allow-list instead. An allow-list of "free-text fields"
 * would leave the next tool's new field unsanitised, which is the same drift [McpPiiMasker] was
 * built to avoid. The rules are no-ops on structural values (amounts, ISO dates, UUIDs, enum
 * statuses contain no control characters, markup or 140+ characters).
 *
 * Order inside [sanitiseText] matters:
 *  1. NFKC — fullwidth brackets, NBSP and compatibility forms become their plain equivalents, so
 *     every later rule sees one spelling.
 *  2. Control, format, line/paragraph separator and private-use code points removed (line breaks
 *     become a space first, so words do not fuse).
 *  3. Link and image markup, HTML tags and URLs replaced with a placeholder.
 *  4. IBAN- and phone-shaped substrings masked (the masker only matches a WHOLE value).
 *  5. Near-variants of the untrusted-data markers neutralised: matched after case fold, whitespace
 *     collapse and Cyrillic/Greek homoglyph folding.
 *  6. Length cap [FREE_TEXT_CAP] (SEPA unstructured remittance maximum) with a truncation marker.
 *
 * Pure application-layer code; an object, so it cannot be left un-injected.
 */
object McpToolResultSanitiser {

    /** SEPA unstructured remittance information maximum — the longest free text a payer can send. */
    const val FREE_TEXT_CAP = 140
    const val TRUNCATION_MARKER = "…"
    const val LINK_PLACEHOLDER = "(link removed)"
    const val IMAGE_PLACEHOLDER = "(image removed)"
    const val MARKUP_PLACEHOLDER = "(markup removed)"
    const val MARKER_PLACEHOLDER = "(marker removed)"
    const val MASK = "***"

    /** Hosts a URL-by-contract field may point at. Only `https`, no query, no fragment, no userinfo. */
    val CDN_HOSTS: Set<String> = setOf("cdn.openbank.example")

    /** Field names whose value is a URL by contract (merchant logo from enrichment). */
    private val URL_FIELDS = setOf("logourl")

    private val factory = JsonNodeFactory.instance

    /** Returns a sanitised deep copy of [node]; the input is never mutated. */
    fun sanitise(node: JsonNode): JsonNode = when {
        node.isObject -> factory.objectNode().also { out -> sanitiseObject(node, out) }
        node.isArray -> factory.arrayNode().apply { node.forEach { add(sanitise(it)) } }
        node.isTextual -> TextNode.valueOf(sanitiseText(node.asText()))
        else -> node
    }

    private fun sanitiseObject(node: JsonNode, out: ObjectNode) {
        node.properties().forEach { (name, value) ->
            if (name.lowercase() in URL_FIELDS) {
                // Dropped, not placeholdered: a URL field holding a placeholder is still rendered
                // as an image source by a markdown client.
                if (value.isTextual && isAllowedCdnUrl(value.asText())) out.set<JsonNode>(name, value)
            } else {
                out.set<JsonNode>(name, sanitise(value))
            }
        }
    }

    fun isAllowedCdnUrl(value: String): Boolean {
        val m = CDN_URL.matchEntire(value) ?: return false
        return m.groupValues[1].lowercase() in CDN_HOSTS
    }

    fun sanitiseText(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = stripControlAndFormat(s)
        s = neutraliseMarkup(s)
        s = maskEmbeddedIdentifiers(s)
        s = neutraliseMarkers(s)
        s = WHITESPACE_RUN.replace(s, " ").trim()
        return cap(s)
    }

    internal fun stripControlAndFormat(s: String): String {
        val sb = StringBuilder(s.length)
        s.codePoints().forEach { cp ->
            when {
                cp == '\n'.code ||
                    cp == '\r'.code ||
                    cp == '\t'.code ||
                    cp == LINE_SEPARATOR ||
                    cp == PARAGRAPH_SEPARATOR -> sb.append(' ')
                isStripped(cp) -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private fun isStripped(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
        -> true
        else -> cp in TAG_CHARS
    }

    internal fun neutraliseMarkup(input: String): String {
        var s = input
        s = MD_IMAGE.replace(s, IMAGE_PLACEHOLDER)
        s = MD_LINK.replace(s) { "${it.groupValues[1]} $LINK_PLACEHOLDER" }
        s = AUTOLINK.replace(s, LINK_PLACEHOLDER)
        s = HTML_TAG.replace(s, MARKUP_PLACEHOLDER)
        s = DANGLING_HTML.replace(s, MARKUP_PLACEHOLDER)
        s = URL.replace(s, LINK_PLACEHOLDER)
        s = SCRIPT_SCHEME.replace(s, LINK_PLACEHOLDER)
        // Structure that survives without a URL: `![alt][ref]` image syntax and `[ref]: x` definitions.
        s = s.replace("![", "[")
        s = REF_DEFINITION.replace(s) { "${it.groupValues[1]} -" }
        return s
    }

    internal fun maskEmbeddedIdentifiers(input: String): String {
        val s = IBAN_IN_TEXT.replace(input) { m ->
            val compact = m.value.replace(" ", "")
            "****" + compact.takeLast(IBAN_TAIL)
        }
        // A phone number only inside free text (a letter present): a bare digit string here is an
        // identifier the masker has already judged by its field name.
        return if (s.any { it.isLetter() }) PHONE_IN_TEXT.replace(s, MASK) else s
    }

    /**
     * Neutralises any spelling of the marker phrase a lenient reader could take for a marker:
     * the match runs on a folded skeleton (case, whitespace, homoglyphs) mapped back to the
     * original indices, and the matched span is replaced.
     */
    internal fun neutraliseMarkers(s: String): String {
        val skeleton = StringBuilder()
        val origin = ArrayList<Int>()
        var i = 0
        var lastWasSpace = false
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val folded = (CONFUSABLES[cp] ?: String(Character.toChars(cp))).lowercase()
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!lastWasSpace) {
                    skeleton.append(' ')
                    origin.add(i)
                }
                lastWasSpace = true
            } else {
                folded.forEach { c ->
                    skeleton.append(c)
                    origin.add(i)
                }
                lastWasSpace = false
            }
            i += Character.charCount(cp)
        }
        val hits = MARKER_PHRASE.findAll(skeleton).toList()
        if (hits.isEmpty()) return s
        val out = StringBuilder()
        var cursor = 0
        hits.forEach { m ->
            val start = origin[m.range.first]
            val endCp = origin[m.range.last]
            val end = endCp + Character.charCount(s.codePointAt(endCp))
            out.append(s, cursor, start).append(MARKER_PLACEHOLDER)
            cursor = end
        }
        out.append(s, cursor, s.length)
        return out.toString()
    }

    internal fun cap(s: String): String {
        if (s.length <= FREE_TEXT_CAP) return s
        var cut = FREE_TEXT_CAP - TRUNCATION_MARKER.length
        if (Character.isLowSurrogate(s[cut])) cut-- // never split a surrogate pair
        return s.substring(0, cut) + TRUNCATION_MARKER
    }

    private const val LINE_SEPARATOR = '\u2028'.code
    private const val PARAGRAPH_SEPARATOR = '\u2029'.code
    private const val IBAN_TAIL = 4

    /** Unicode tag block U+E0000..U+E007F ("ASCII smuggling"). */
    private val TAG_CHARS = "\uDB40\uDC00".codePointAt(0).."\uDB40\uDC7F".codePointAt(0)

    private val WHITESPACE_RUN = Regex(" {2,}")
    private val CDN_URL = Regex("""^https://([A-Za-z0-9.-]+)/[A-Za-z0-9/._-]*$""")
    private val MD_IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val AUTOLINK = Regex("""<\s*[A-Za-z][A-Za-z0-9+.-]*:[^>]*>""")
    private val HTML_TAG = Regex("""<\s*/?\s*[A-Za-z][^>]*>""")
    private val DANGLING_HTML = Regex("""<\s*/?\s*[A-Za-z][A-Za-z0-9]*\b""")
    private val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S*""")
    private val SCRIPT_SCHEME = Regex("""(?i)\b(javascript|vbscript|data|file)\s*:\S*""")
    private val REF_DEFINITION = Regex("""\[([^\]]+)]\s*:""")
    private val IBAN_IN_TEXT = Regex("""\b[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]){10,30}\b""")
    private val PHONE_IN_TEXT = Regex("""(?<![\w+])(?:\+\d{1,3} ?)?\d{3} ?\d{3} ?\d{3}(?!\w)""")
    private val MARKER_PHRASE = Regex("""untrusted ?tool ?data""")

    /** Cyrillic/Greek letters that render as the Latin letters of the marker words. */
    private val CONFUSABLES: Map<Int, String> = (
        "\u0410a\u0430a\u0412b\u0415e\u0435e\u041Dh\u041Ak\u041Cm\u041Eo\u043Eo\u0420p\u0440p" +
            "\u0421c\u0441c\u0422t\u0425x\u0445x\u0423y\u0443y\u0456i\u0406i\u0455s\u0405s\u0458j" +
            "\u0391a\u0395e\u039Fo\u03BFo\u03A4t\u039Dn\u0399i\u03B9i\u0392b\u039Ak\u039Cm\u03A1p"
        ).chunked(2).associate { it[0].code to it[1].toString() }
}
