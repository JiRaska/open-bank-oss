// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.domain

/**
 * Dependency-free HTML → text / markdown reduction (ADR-0387 D3). Deliberately lossy: scripts,
 * styles and markup are dropped; headings, paragraphs, list items and line breaks survive as
 * structure. It is evidence text for a reviewer, not a faithful rendering.
 *
 * Linear by construction: one left-to-right scan with `indexOf`, no backtracking regex over the
 * page (a lazy `.*?` up to a back-referenced close tag is quadratic on many unclosed openers — a
 * page could pin a CPU). An unclosed comment or dropped element swallows the rest of the input.
 * Input is capped at [MAX_INPUT_CHARS] before the scan.
 */
object HtmlTextExtractor {
    const val MAX_INPUT_CHARS = 4_000_000
    private val DROPPED = setOf("script", "style", "noscript", "template", "svg", "head")
    private val BLOCKS =
        setOf("p", "div", "section", "article", "tr", "table", "ul", "ol", "br", "hr", "blockquote", "pre")
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]{1,8});")
    private val SPACES = Regex("[ \\t\\u00a0]+")
    private val BLANK_LINES = Regex("\\n{3,}")
    private const val HEX = 16
    private const val DECIMAL = 10
    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private val NAMED = mapOf(
        "&nbsp;" to " ",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
    )

    fun extract(html: String, mode: FetchMode): String {
        val input = if (html.length > MAX_INPUT_CHARS) html.substring(0, MAX_INPUT_CHARS) else html
        val lower = input.lowercase()
        val out = StringBuilder(minOf(input.length, MAX_INPUT_CHARS))
        var i = 0
        while (i < input.length) {
            val lt = input.indexOf('<', i)
            if (lt < 0) {
                out.append(input, i, input.length)
                break
            }
            out.append(input, i, lt)
            i = tag(input, lower, lt, mode, out)
        }
        return finish(decodeEntities(out.toString()))
    }

    /** Handles the markup starting at [lt]; returns where the scan continues. */
    private fun tag(input: String, lower: String, lt: Int, mode: FetchMode, out: StringBuilder): Int {
        if (lower.startsWith(COMMENT_OPEN, lt)) {
            val end = lower.indexOf(COMMENT_CLOSE, lt + COMMENT_OPEN.length)
            return if (end < 0) input.length else end + COMMENT_CLOSE.length
        }
        val gt = input.indexOf('>', lt)
        if (gt < 0) return input.length
        val closing = lower.startsWith("</", lt)
        val name = tagName(lower, if (closing) lt + 2 else lt + 1)
        when {
            !closing && name in DROPPED -> return skipElement(input, lower, name, gt)
            name in HEADINGS -> out.append(
                if (!closing && mode == FetchMode.MARKDOWN) "\n\n${"#".repeat(name[1].digitToInt())} " else "\n\n",
            )
            !closing && name == "li" -> out.append(if (mode == FetchMode.MARKDOWN) "\n- " else "\n")
            name in BLOCKS -> out.append('\n')
        }
        return gt + 1
    }

    /** Skips a dropped element through its close tag; an unclosed one swallows the rest. */
    private fun skipElement(input: String, lower: String, name: String, from: Int): Int {
        val end = lower.indexOf("</$name", from)
        val endGt = if (end < 0) -1 else input.indexOf('>', end)
        return if (endGt < 0) input.length else endGt + 1
    }

    private fun tagName(lower: String, start: Int): String {
        var j = start
        while (j < lower.length && (lower[j].isLetterOrDigit())) j++
        return lower.substring(start, j)
    }

    private fun finish(s: String): String = s.lines().joinToString("\n") { SPACES.replace(it, " ").trim() }
        .let { BLANK_LINES.replace(it, "\n\n") }
        .trim()

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
