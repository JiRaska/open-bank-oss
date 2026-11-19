// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.regwatcher.application.port.Assessment
import com.openbank.regwatcher.application.port.ObligationAssessorPort
import com.openbank.regwatcher.domain.Obligation
import com.openbank.regwatcher.domain.SourceFetch
import com.openbank.regwatcher.domain.sha256Hex
import org.jboss.logging.Logger
import java.security.SecureRandom

/**
 * The model step, behind the shared LiteLLM gateway (ADR-0174) with the registered prompt
 * (ADR-0148, `prompts/regulatory-obligations-watcher/system.v1.md`, read byte-for-byte so the
 * audited prompt_hash resolves to the registry file).
 *
 * Untrusted content discipline (ADR-0387 D4):
 *  - the page text goes ONLY into the `<page>` data section, re-wrapped in delimiters carrying a
 *    nonce the page could not know, after neutralising every `<<<`/`>>>` run;
 *  - the answer is parsed as ONE closed JSON object: `verdict` from the task's closed vocabulary,
 *    `obligation_ids` intersected with the ids that were supplied, `quote` accepted only when it is a
 *    verbatim passage of the page. Anything else in the answer — prose, extra keys, a "tool call" —
 *    is discarded, never interpreted. A schema violation answers null (the finding is deferred).
 *  - there is no tool surface: nothing the model writes can cause a fetch, an issue or a write.
 */
class LlmObligationAssessor(
    private val gateway: LlmGatewayPort,
    override val modelId: String,
    private val systemPrompt: String,
    private val maxContentChars: Int,
) : ObligationAssessorPort {
    private val log = Logger.getLogger(LlmObligationAssessor::class.java)
    private val mapper = ObjectMapper()
    private val random = SecureRandom()

    override val promptHash: String = sha256Hex(systemPrompt.toByteArray())

    override val maxTokensPerCall: Int =
        (systemPrompt.length + maxContentChars + ROWS_ALLOWANCE_CHARS) / CHARS_PER_TOKEN + ANSWER_TOKENS

    override suspend fun assessChange(obligations: List<Obligation>, fetch: SourceFetch): Assessment? =
        assess(TASK_CHANGE, obligations, fetch, CHANGE_VERDICTS)

    override suspend fun assessCadence(obligation: Obligation, fetch: SourceFetch): Assessment? =
        assess(TASK_CADENCE, listOf(obligation), fetch, CADENCE_VERDICTS)

    @Suppress("ReturnCount")
    private suspend fun assess(
        task: String,
        rows: List<Obligation>,
        fetch: SourceFetch,
        verdicts: Set<String>,
    ): Assessment? {
        val page = innerText(fetch.content) ?: run {
            log.warnf("content for %s carries no untrusted-content envelope — not sent to the model", fetch.url)
            return null
        }
        val cut = page.length > maxContentChars
        val sent = page.take(maxContentChars)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val user = buildString {
            append("<task>").append(task).append("</task>\n<obligations>\n")
            rows.forEach { o ->
                append("- id: ").append(o.id).append("\n  name: ").append(o.name)
                    .append("\n  legal_basis: ").append(o.legalBasis)
                    .append("\n  cadence: ").append(o.cadence)
                    .append("\n  cadence_confidence: ").append(o.cadenceConfidence).append('\n')
            }
            append("</obligations>\n<page url=\"").append(fetch.url).append("\" sha256=\"").append(fetch.sha256)
                .append("\" truncated=\"").append(cut).append("\">\n")
            append("<<<BEGIN_UNTRUSTED_PAGE ").append(nonce).append(">>>\n")
            append(neutralise(sent)).append('\n')
            append("<<<END_UNTRUSTED_PAGE ").append(nonce).append(">>>\n</page>")
        }
        val answer = gateway.chat(systemPrompt, user) ?: return null
        val tokens = (systemPrompt.length + user.length + answer.length) / CHARS_PER_TOKEN
        return parse(answer, verdicts, rows.map { it.id }.toSet(), page)?.copy(tokensUsed = tokens)
    }

    @Suppress("ReturnCount")
    internal fun parse(answer: String, verdicts: Set<String>, ids: Set<String>, page: String): Assessment? {
        val json = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject } ?: run {
            log.warn("model answer is not one JSON object — discarded")
            return null
        }
        val verdict = node.path("verdict").asText("")
        if (verdict !in verdicts) {
            log.warnf("model verdict %s is outside the closed vocabulary — discarded", verdict.take(MAX_LOG))
            return null
        }
        val extra = node.fieldNames().asSequence().filterNot { it in SCHEMA_KEYS }.toList()
        if (extra.isNotEmpty()) {
            log.warnf(
                "model answer carried unknown keys %s — ignored, never acted on",
                extra.take(MAX_KEYS),
            )
        }
        val chosen = node.path("obligation_ids").takeIf {
            it.isArray
        }?.map { it.asText() }?.filter { it in ids }.orEmpty()
        val rawQuote = node.path("quote").asText("").trim().take(MAX_QUOTE)
        val quote = rawQuote.takeIf { it.isNotEmpty() && normalise(page).contains(normalise(it)) }
        if (rawQuote.isNotEmpty() && quote == null) {
            log.warn("model quote is not a verbatim passage of the page — discarded")
            return null
        }
        if (verdict in QUOTE_REQUIRED && quote == null) {
            log.warnf("verdict %s without a verifiable quote — discarded", verdict)
            return null
        }
        return Assessment(
            verdict = verdict,
            summary = node.path("summary").asText("").take(MAX_SUMMARY),
            quote = quote,
            obligationIds = chosen,
            tokensUsed = 0,
        )
    }

    companion object {
        const val TASK_CHANGE = "SOURCE_CHANGED"
        const val TASK_CADENCE = "CADENCE_CHECK"
        val CHANGE_VERDICTS = setOf("AMENDED", "NO_MATERIAL_CHANGE", "UNCLEAR")
        val CADENCE_VERDICTS = setOf("SUPPORTS", "CONTRADICTS", "NOT_FOUND")
        private val QUOTE_REQUIRED = setOf("AMENDED", "SUPPORTS", "CONTRADICTS")
        private val SCHEMA_KEYS = setOf("verdict", "obligation_ids", "summary", "quote")
        private val BEGIN = Regex("^<<<BEGIN_UNTRUSTED_WEB_CONTENT [0-9a-f]+>>>$", RegexOption.MULTILINE)
        private val END = Regex("^<<<END_UNTRUSTED_WEB_CONTENT [0-9a-f]+>>>$", RegexOption.MULTILINE)
        private val ANGLE_RUN = Regex("<{3,}|>{3,}")
        private val WS = Regex("\\s+")
        private const val CHARS_PER_TOKEN = 4
        private const val ANSWER_TOKENS = 600
        private const val ROWS_ALLOWANCE_CHARS = 4000
        private const val NONCE_BYTES = 12
        private const val MAX_QUOTE = 300
        private const val MAX_SUMMARY = 1200
        private const val MAX_LOG = 40
        private const val MAX_KEYS = 5

        /** The text between the broker's delimiters, or null when the envelope is not there. */
        fun innerText(content: String?): String? {
            if (content == null) return null
            val b = BEGIN.find(content) ?: return null
            val e = END.findAll(content).lastOrNull() ?: return null
            if (e.range.first <= b.range.last) return null
            return content.substring(b.range.last + 1, e.range.first).trim()
        }

        fun neutralise(text: String): String = ANGLE_RUN.replace(text) { m -> m.value.toCharArray().joinToString(" ") }

        private fun normalise(s: String) = WS.replace(s, " ").trim()
    }
}
