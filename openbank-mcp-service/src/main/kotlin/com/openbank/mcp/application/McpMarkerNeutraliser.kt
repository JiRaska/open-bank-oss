// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import java.text.Normalizer

/**
 * Neutralises spellings of the untrusted-data marker phrase that a lenient reader could take for a
 * marker (ADR-0383 D7). The match runs on a skeleton: NFD with combining marks dropped, case
 * folded, confusables mapped to their Latin target (UTS #39 style), any run of non-letters (digits
 * included) between the words. Digits are therefore NOT mapped as confusables in the table: the
 * digit look-alikes of `tool` (`t00l`, `too1`, `too|`) are spelled out in the phrase instead.
 *
 * ICU4J is not on this service's runtime classpath, so `SpoofChecker.getSkeleton` is not used and
 * no dependency is added for it. The vendored table below covers the marker's own letters only.
 * Residual: a look-alike from a script not in the table, and not folded by NFKC, still evades the
 * skeleton. The markers are defence in depth (ADR-0383 D7), not the boundary. The skeleton is mapped back to the original indices and the span replaced.
 */
internal object McpMarkerNeutraliser {

    const val PLACEHOLDER = "(marker removed)"

    private val PHRASE = Regex("""untrusted[^\p{L}]*+t[o0]{2}[l1|][^\p{L}]*+data""")
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
            "ɑaılǀlIl" +
            // Latin small capitals (U+1D00 block and IPA extensions).
            "ᴀaᴅdᴇeʟlɴnᴏoʀrꜱsᴛtᴜu" +
            // Lisu (U+A4D0..U+A4FF).
            "ꓮaꓓdꓰeꓡlꓠnꓳoꓣrꓢsꓔtꓴu" +
            // Other Latin look-alikes: turned, reversed and hooked forms.
            "ɐaɗdɖdəeǝeɹrɾrʂsƫtƭtʊuɳn"
        ).chunked(2).associate { it[0].code to it[1].toString() }
}
