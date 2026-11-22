// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import java.text.Normalizer

/**
 * Link, image and HTML markup removal for [McpToolResultSanitiser] (ADR-0383 D7, exfiltration via
 * rendered URLs), plus the decoding that must precede it so an encoded URL is seen decoded.
 *
 * Every pattern is linear: possessive quantifiers, negated classes that stop at the next opener,
 * and scheme/`www.` matches anchored at a word start.
 */
internal object McpMarkupNeutraliser {

    const val LINK_PLACEHOLDER = "(link removed)"
    const val IMAGE_PLACEHOLDER = "(image removed)"
    const val MARKUP_PLACEHOLDER = "(markup removed)"

    private const val HEX = 16

    /** Decoding rounds: `%2568` needs two; a bound keeps nested encodings from costing more. */
    private const val MAX_DECODE_ROUNDS = 3

    /** Replaces markup; repeats on the combining-mark-stripped text when markup only shows there. */
    fun neutralise(input: String): String {
        val s = replaceMarkup(input)
        val unmarked = COMBINING.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        return if (unmarked != s && PROBES.any { it.containsMatchIn(unmarked) }) replaceMarkup(unmarked) else s
    }

    /** Percent (printable ASCII only) and HTML-entity decoding, repeated to a fixpoint (max 3 rounds). */
    fun decode(input: String): String {
        var s = input
        repeat(MAX_DECODE_ROUNDS) {
            val next = decodeOnce(s)
            if (next == s) return s
            s = next
        }
        return s
    }

    private fun decodeOnce(input: String): String {
        var s = PERCENT.replace(input) { m ->
            val c = m.groupValues[1].toInt(HEX).toChar()
            if (c in ' '..'~') c.toString() else m.value
        }
        s = NUMERIC_ENTITY.replace(s) { m ->
            val (dec, hex) = m.destructured
            val cp = if (dec.isNotEmpty()) dec.toIntOrNull() else hex.toIntOrNull(HEX)
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""
        }
        return NAMED_ENTITY.replace(s) { m -> NAMED_ENTITIES[m.groupValues[1].lowercase()] ?: m.value }
    }

    private fun replaceMarkup(input: String): String {
        var s = input
        s = MD_IMAGE.replace(s, IMAGE_PLACEHOLDER)
        s = MD_LINK.replace(s) { "${it.groupValues[1]} $LINK_PLACEHOLDER" }
        s = AUTOLINK.replace(s, LINK_PLACEHOLDER)
        s = HTML_TAG.replace(s, MARKUP_PLACEHOLDER)
        s = DANGLING_HTML.replace(s, MARKUP_PLACEHOLDER)
        s = URL.replace(s, LINK_PLACEHOLDER)
        s = WWW_URL.replace(s, LINK_PLACEHOLDER)
        s = PROTOCOL_RELATIVE.replace(s, LINK_PLACEHOLDER)
        s = SCRIPT_SCHEME.replace(s, LINK_PLACEHOLDER)
        // Structure that survives without a URL: `![alt][ref]` image syntax and `[ref]: x` definitions.
        s = s.replace("![", "[").replace("](", "] (")
        return REF_DEFINITION.replace(s) { "${it.groupValues[1]} -" }
    }

    private val COMBINING = Regex("""\p{Mn}++""")
    private val PERCENT = Regex("""%([0-9A-Fa-f]{2})""")
    private val NUMERIC_ENTITY = Regex("""&#(?:([0-9]{1,7})|[xX]([0-9A-Fa-f]{1,6}));?""")
    private val NAMED_ENTITY = Regex("""&([A-Za-z]{2,8});""")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "colon" to ":",
        "sol" to "/", "period" to ".", "lpar" to "(", "rpar" to ")", "lsqb" to "[", "rsqb" to "]",
        "excl" to "!", "nbsp" to " ", "commat" to "@", "num" to "#", "quest" to "?", "equals" to "=",
    )
    private val MD_IMAGE = Regex("""!\[[^\[\]]*+]\([^()]*+\)""")
    private val MD_LINK = Regex("""\[([^\[\]]*+)]\([^()]*+\)""")
    private val AUTOLINK = Regex("""<\s*+[A-Za-z][A-Za-z0-9+.-]*+:[^<>]*+>""")
    private val HTML_TAG = Regex("""<\s*+/?\s*+[A-Za-z][^<>]*+>""")
    private val DANGLING_HTML = Regex("""<\s*+/?\s*+[A-Za-z][A-Za-z0-9]*+""")
    private val URL = Regex("""(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*+://\S*+""")
    private val WWW_URL = Regex("""(?i)(?<![a-z0-9.-])www\.\S*+""")
    private val PROTOCOL_RELATIVE = Regex("""(?<![A-Za-z0-9:/])//[A-Za-z0-9-]++(?:\.[A-Za-z0-9-]++)++\S*+""")
    private val SCRIPT_SCHEME = Regex("""(?i)(?<![a-z0-9])(?:javascript|vbscript|data|file)\s*+:\S*+""")
    private val REF_DEFINITION = Regex("""\[([^\[\]]++)]\s*+:""")
    private val PROBES = listOf(URL, WWW_URL, PROTOCOL_RELATIVE, SCRIPT_SCHEME, HTML_TAG, DANGLING_HTML)
}
