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
 * **Deny-by-default over string values and object keys, not an allow-list of field names.** Every
 * string goes through [sanitiseText]. An allow-list of "free-text fields" would leave the next
 * tool's new field unsanitised, the drift [McpPiiMasker] was built to avoid. Two exceptions:
 *  - a URL-by-contract field ([URL_FIELDS]) is checked against the bank CDN allow-list instead;
 *  - an identifier or amount field ([isIdentifierField]) skips only the embedded IBAN/phone masking,
 *    so a surrogate id or an amount a model chains into the next call is never altered.
 *
 * Order inside [sanitiseText] matters:
 *  1. Pre-truncation to [PRE_CAP_CODE_POINTS] code points, so every later step runs on bounded input
 *     (an unbounded value would otherwise make the pattern steps the cost of the whole response).
 *  2. NFKC; then control, format, tag, private-use and line/paragraph separator code points removed.
 *  3. Percent- and HTML-entity-encoded characters decoded, then step 2 repeated.
 *  4. Link and image markup, HTML tags and URLs replaced ([McpMarkupNeutraliser]).
 *  5. IBAN-shaped ([McpIbanDetection]) and phone-shaped substrings masked.
 *  6. Marker look-alikes neutralised ([McpMarkerNeutraliser]).
 *  7. Length cap [FREE_TEXT_CAP] (SEPA unstructured remittance maximum) with a truncation marker.
 *
 * Pure application-layer code; objects, so nothing can be left un-injected.
 */
object McpToolResultSanitiser {

    /** SEPA unstructured remittance information maximum — the longest free text a payer can send. */
    const val FREE_TEXT_CAP = 140

    /** Input bound before any normalisation or pattern step. */
    const val PRE_CAP_CODE_POINTS = FREE_TEXT_CAP * 4
    const val TRUNCATION_MARKER = "…"
    const val MARKER_PLACEHOLDER = McpMarkerNeutraliser.PLACEHOLDER
    const val MASK = "***"

    /** Hosts a URL-by-contract field may point at. Only `https`, no query, no fragment, no userinfo. */
    val CDN_HOSTS: Set<String> = setOf("cdn.openbank.example")

    /** Field names whose value is a URL by contract (merchant logo from enrichment). */
    private val URL_FIELDS = setOf("logourl")

    /** Field-name fragments for amounts and dates; with a trailing `id`, these skip IBAN/phone masking. */
    private val IDENTIFIER_FRAGMENTS = listOf("amount", "currency", "date", "balance")

    private val factory = JsonNodeFactory.instance

    /** Returns a sanitised deep copy of [node]; the input is never mutated. */
    fun sanitise(node: JsonNode): JsonNode = sanitiseNode(node, fieldName = null)

    private fun sanitiseNode(node: JsonNode, fieldName: String?): JsonNode = when {
        node.isObject -> factory.objectNode().also { out -> sanitiseObject(node, out) }
        node.isArray -> factory.arrayNode().apply { node.forEach { add(sanitiseNode(it, fieldName)) } }
        node.isTextual -> TextNode.valueOf(sanitiseText(node.asText(), maskIdentifiers = !isIdentifierField(fieldName)))
        else -> node
    }

    private fun sanitiseObject(node: JsonNode, out: ObjectNode) {
        node.properties().forEach { (name, value) ->
            if (name.lowercase() in URL_FIELDS) {
                // Dropped, not placeholdered: a URL field holding a placeholder is still rendered
                // as an image source by a markdown client.
                if (value.isTextual && isAllowedCdnUrl(value.asText())) out.set<JsonNode>(name, value)
            } else {
                // Keys are data too: a poisoned upstream document can carry instructions in a key.
                val key = sanitiseText(name, maskIdentifiers = false).ifEmpty { "_" }
                out.set<JsonNode>(uniqueKey(key, out), sanitiseNode(value, name))
            }
        }
    }

    private fun uniqueKey(key: String, out: ObjectNode): String {
        if (!out.has(key)) return key
        var n = 2
        while (out.has("${key}_$n")) n++
        return "${key}_$n"
    }

    /** `accountId`, `paymentId`, `id`, amounts, currencies, dates: values a model chains into the next call. */
    fun isIdentifierField(fieldName: String?): Boolean {
        val key = fieldName?.lowercase() ?: return false
        return key.endsWith("id") || IDENTIFIER_FRAGMENTS.any { key.contains(it) }
    }

    fun isAllowedCdnUrl(value: String): Boolean {
        val m = CDN_URL.matchEntire(value) ?: return false
        return m.groupValues[1].lowercase() in CDN_HOSTS
    }

    fun sanitiseText(raw: String, maskIdentifiers: Boolean = true): String {
        var s = preTruncate(raw)
        s = McpControlChars.strip(Normalizer.normalize(s, Normalizer.Form.NFKC))
        s = McpControlChars.strip(McpMarkupNeutraliser.decode(s))
        s = McpMarkupNeutraliser.neutralise(s)
        if (maskIdentifiers) s = PHONE_IN_TEXT.replace(McpIbanDetection.mask(s), MASK)
        s = McpMarkerNeutraliser.neutralise(s)
        return cap(WHITESPACE_RUN.replace(s, " ").trim())
    }

    /** First [PRE_CAP_CODE_POINTS] code points; never splits a surrogate pair. */
    internal fun preTruncate(s: String): String {
        if (s.length <= PRE_CAP_CODE_POINTS) return s
        return s.substring(0, s.offsetByCodePoints(0, minOf(PRE_CAP_CODE_POINTS, s.codePointCount(0, s.length))))
    }

    internal fun cap(s: String): String {
        if (s.length <= FREE_TEXT_CAP) return s
        var cut = FREE_TEXT_CAP - TRUNCATION_MARKER.length
        if (Character.isLowSurrogate(s[cut])) cut-- // never split a surrogate pair
        return s.substring(0, cut) + TRUNCATION_MARKER
    }

    private val WHITESPACE_RUN = Regex(" {2,}+")
    private val CDN_URL = Regex("""^https://([A-Za-z0-9.-]++)/[A-Za-z0-9/._-]*+$""")

    /**
     * 3-3-3 and 3-2-2-2 groupings, optional `+CC`/`00CC`, separated by space, dot or hyphen. Not a
     * digit group inside a longer number (`1 234 567 890`) or glued to a word (`PAY-123456789`) or
     * followed by a bank code (`123456789/0100`).
     */
    private val PHONE_IN_TEXT = Regex(
        """(?<![\p{L}\p{N}_+]|\p{N}[ .\-/]|[\p{L}\p{N}][.\-/])""" +
            """(?:(?:\+|00)\d{1,3}[ .\-]?)?\d{3}(?:[ .\-]?\d{3}[ .\-]?\d{3}|[ .\-]?\d{2}[ .\-]?\d{2}[ .\-]?\d{2})""" +
            """(?![\p{L}\p{N}_/]|[ .\-/]\p{N})""",
    )
}

/** Control, format, tag, private-use and separator code points (ADR-0383 D7). */
internal object McpControlChars {

    private const val LINE_SEPARATOR = '\u2028'.code
    private const val PARAGRAPH_SEPARATOR = '\u2029'.code

    /** Unicode tag block U+E0000..U+E007F ("ASCII smuggling"). */
    private val TAG_CHARS = "\uDB40\uDC00".codePointAt(0).."\uDB40\uDC7F".codePointAt(0)
    private val BREAKS = setOf('\n'.code, '\r'.code, '\t'.code, LINE_SEPARATOR, PARAGRAPH_SEPARATOR)

    /** Removes them; a line break, tab or separator becomes a space so words do not fuse. */
    fun strip(s: String): String {
        val sb = StringBuilder(s.length)
        s.codePoints().forEach { cp ->
            when {
                cp in BREAKS -> sb.append(' ')
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
}
