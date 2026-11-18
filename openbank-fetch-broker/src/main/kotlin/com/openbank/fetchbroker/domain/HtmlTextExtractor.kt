// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.domain

/**
 * Dependency-free HTML → text / markdown reduction (ADR-0387 D3). Deliberately lossy: scripts,
 * styles and markup are dropped; headings, paragraphs, list items and line breaks survive as
 * structure. It is evidence text for a reviewer, not a faithful rendering.
 */
object HtmlTextExtractor {
    private val DROP = Regex("(?is)<(script|style|noscript|template|svg|head)\\b.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val HEADING = Regex("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>")
    private val LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
    private val BLOCK_BREAK = Regex("(?i)</?(p|div|section|article|tr|table|ul|ol|br|hr|blockquote|pre)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
    private val SPACES = Regex("[ \\t\\u00a0]+")
    private val BLANK_LINES = Regex("\\n{3,}")
    private const val HEX = 16
    private const val DECIMAL = 10
    private val NAMED = mapOf(
        "&nbsp;" to " ",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
    )

    fun extract(html: String, mode: FetchMode): String {
        var s = COMMENT.replace(html, "")
        s = DROP.replace(s, "")
        s = HEADING.replace(s) { m ->
            val text = TAG.replace(m.groupValues[2], "").trim()
            if (mode == FetchMode.MARKDOWN) "\n\n${"#".repeat(m.groupValues[1].toInt())} $text\n\n" else "\n\n$text\n\n"
        }
        s = LIST_ITEM.replace(s, if (mode == FetchMode.MARKDOWN) "\n- " else "\n")
        s = BLOCK_BREAK.replace(s, "\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        return s.lines().joinToString("\n") { SPACES.replace(it, " ").trim() }
            .let { BLANK_LINES.replace(it, "\n\n") }
            .trim()
    }

    private fun decodeEntities(s: String): String {
        var out = NUMERIC_ENTITY.replace(s) { m ->
            val radix = if (m.groupValues[1].isNotEmpty()) HEX else DECIMAL
            val cp = m.groupValues[2].toIntOrNull(radix)
            // U+0000 is never emitted (fleet NUL rule); invalid code points are dropped.
            if (cp == null || cp == 0 || !Character.isValidCodePoint(cp)) "" else String(Character.toChars(cp))
        }
        NAMED.forEach { (k, v) -> out = out.replace(k, v) }
        return out.replace("&amp;", "&")
    }
}
