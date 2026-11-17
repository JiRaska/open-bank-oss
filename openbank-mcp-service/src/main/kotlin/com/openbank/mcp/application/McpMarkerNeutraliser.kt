// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import java.text.Normalizer

/**
 * Neutralises spellings of the untrusted-data marker phrase that a lenient reader could take for a
 * marker (ADR-0383 D7). The match runs on a skeleton: NFD with combining marks dropped, case
 * folded, confusables mapped to their Latin target (UTS #39 style), any non-alphanumeric run
 * between the words. The skeleton is mapped back to the original indices and the span replaced.
 */
internal object McpMarkerNeutraliser {

    const val PLACEHOLDER = "(marker removed)"

    private val PHRASE = Regex("""untrusted[^\p{L}\p{N}]*+tool[^\p{L}\p{N}]*+data""")
    private val COMBINING = Regex("""\p{Mn}++""")
    private val SMALL_CHEROKEE = 'ꭰ'.code..'ꮿ'.code
    private const val CHEROKEE_BASE = 'Ꭰ'.code

    fun neutralise(s: String): String {
        val skeleton = StringBuilder()
        val origin = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            skeletonOf(cp).forEach { c ->
                skeleton.append(c)
                origin.add(i)
            }
            i += Character.charCount(cp)
        }
        val hits = PHRASE.findAll(skeleton).toList()
        if (hits.isEmpty()) return s
        val out = StringBuilder()
        var cursor = 0
        hits.forEach { m ->
            val start = origin[m.range.first]
            val endCp = origin[m.range.last]
            if (start >= cursor) {
                out.append(s, cursor, start).append(PLACEHOLDER)
                cursor = endCp + Character.charCount(s.codePointAt(endCp))
            }
        }
        return out.append(s, cursor, s.length).toString()
    }

    private fun skeletonOf(cp: Int): String {
        val upperCherokee = if (cp in SMALL_CHEROKEE) cp - SMALL_CHEROKEE.first + CHEROKEE_BASE else cp
        val mapped = CONFUSABLES[upperCherokee] ?: String(Character.toChars(upperCherokee))
        val folded = COMBINING.replace(Normalizer.normalize(mapped, Normalizer.Form.NFD), "").lowercase()
        return buildString { folded.forEach { c -> append(CONFUSABLES[c.code] ?: c.toString()) } }
    }

    /**
     * Minimal UTS #39-style confusables subset for the Latin letters of the marker phrase
     * (u, n, t, r, s, e, d, o, l, a): Cyrillic, Greek, Armenian, Cherokee and Latin look-alikes.
     * Fullwidth and compatibility forms are already folded by NFKC. Pairs of (source, target).
     */
    private val CONFUSABLES: Map<Int, String> = (
        "АaаaЕeеeОoоoЅsѕsԁdгrТtпn" +
            "ոnսuՍuօo" +
            "ΑaαaΕeΟoοoΤtτtυuΝn" +
            "ᎠdᎡrᎢtᎪaᎬeᏚsᏞlᏫo" +
            "ɑaılǀlIl1l|l0o"
        ).chunked(2).associate { it[0].code to it[1].toString() }
}
