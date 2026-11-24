// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

/**
 * IBAN detection for [McpToolResultSanitiser] and [McpPiiMasker] (ADR-0383 D7).
 *
 * An IBAN here is a two-letter country code with an ISO 13616 registry length, two check digits,
 * then exactly that many alphanumerics, in any case, optionally grouped by space, dot, hyphen or
 * slash. It is validated by country and length, deliberately NOT by ISO 7064 mod-97: an RF creditor
 * reference (`RF18…`) passes mod-97 (same algorithm) and is not an IBAN, while a mistyped real IBAN
 * fails it and still identifies an account. Mod-97 would get both cases wrong.
 *
 * This is DETECTION for masking, deliberately distinct from VALIDATION: an argument that must be a
 * real IBAN (`propose_payment`'s `toIban`, an IBAN-form `accountId` in `McpInputBounds`) is checked
 * with libs-domain's `Iban.isValid` (mod-97), the one validator fleet-wide, and this object does not
 * reimplement it. A masker that reused that validator would let a mistyped account number through.
 *
 * Linear: each start position inspects at most the registry length of characters.
 */
internal object McpIbanDetection {

    private const val TAIL = 4
    private const val HEAD = 4
    private const val MASK_PREFIX = "****"
    private const val SEPARATORS = " .-/"
    private val HEAD_SHAPE = Regex("[A-Za-z]{2}[0-9]{2}")

    /** True when the whole (trimmed) [value] is one IBAN. */
    fun isIban(value: String): Boolean = endAt(value, 0) == value.length

    /** Replaces every IBAN in [s] with `****` and its last four characters. */
    fun mask(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        var copied = 0
        while (i < s.length) {
            val end = endAt(s, i)
            if (end > 0) {
                val compact = s.substring(i, end).filter { it.isAsciiAlnum() }
                out.append(s, copied, i).append(MASK_PREFIX).append(compact.takeLast(TAIL).uppercase())
                copied = end
                i = end
            } else {
                i++
            }
        }
        return out.append(s, copied, s.length).toString()
    }

    /**
     * Masks an IBAN-shaped fragment (registry country code, at least one check digit, then
     * alphanumerics) that runs to the end of [s]: used only after a length cut, which can leave an
     * IBAN too short for [mask] to recognise while still carrying most of the account number.
     */
    fun maskTrailingPartial(s: String): String {
        val m = TRAILING_PARTIAL.find(s) ?: return s
        return if (m.groupValues[1].uppercase() in IBAN_LENGTHS) s.substring(0, m.range.first) + MASK_PREFIX else s
    }

    private val TRAILING_PARTIAL = Regex("""(?<![A-Za-z0-9])([A-Za-z]{2})[0-9]{1,2}(?:[ .\-/]?[A-Za-z0-9])*+$""")

    /** Exclusive end of an IBAN starting at [start], or -1. */
    private fun endAt(s: String, start: Int): Int {
        val startsWord = start == 0 || !s[start - 1].isAsciiAlnum()
        val length = if (startsWord && HEAD_SHAPE.matchesAt(s, start)) {
            IBAN_LENGTHS[s.substring(start, start + 2).uppercase()]
        } else {
            null
        }
        return if (length == null) -1 else bodyEnd(s, start + HEAD, length)
    }

    /** Consumes alphanumerics (single separators allowed between them) until [length] are counted. */
    private fun bodyEnd(s: String, from: Int, length: Int): Int {
        var count = HEAD
        var j = from
        while (count < length && j < s.length) {
            val c = s[j]
            val separated = c in SEPARATORS && j + 1 < s.length && s[j + 1].isAsciiAlnum()
            if (!c.isAsciiAlnum() && !separated) break
            if (c.isAsciiAlnum()) count++
            j++
        }
        val ends = j == s.length || !s[j].isAsciiAlnum()
        return if (count == length && ends) j else -1
    }

    private fun Char.isAsciiAlnum() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    /** ISO 13616 registry: country code -> IBAN length. */
    private val IBAN_LENGTHS: Map<String, Int> = (
        "AD24 AE23 AL28 AT20 AZ28 BA20 BE16 BG22 BH22 BI27 BR29 BY28 CH21 CR22 CY28 CZ24 DE22 DJ27 " +
            "DK18 DO28 EE20 EG29 ES24 FI18 FK18 FO18 FR27 GB22 GE22 GI23 GL18 GR27 GT28 HR21 HU28 IE22 " +
            "IL23 IQ23 IS26 IT27 JO30 KW30 KZ20 LB28 LC32 LI21 LT20 LU20 LV21 LY25 MC27 MD24 ME22 MK19 " +
            "MN20 MR27 MT31 MU30 NI28 NL18 NO15 OM23 PK24 PL28 PS29 QA29 RO24 RS22 RU33 SA24 SC31 SD18 " +
            "SE24 SI19 SK24 SM27 SO23 ST25 SV28 TL23 TN24 TR26 UA29 VA22 VG24 XK20 YE30"
        ).split(' ').associate { it.substring(0, 2) to it.substring(2).toInt() }
}
