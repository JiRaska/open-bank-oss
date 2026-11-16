// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.`in`.CreateProposalUseCase
import com.openbank.agent.application.port.out.DownstreamReadPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.MDC

/**
 * `aml_propose_case_decision` (capability `propose.aml.decision`). Kept out of [McpToolRegistry]
 * so the registry stays the catalog + dispatch envelope; this class owns the AML-specific checks.
 */
@ApplicationScoped
class AmlDecisionProposalTool {

    @Inject lateinit var downstream: DownstreamReadPort

    @Inject lateinit var proposals: CreateProposalUseCase

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var charters: CharterRegistry

/**
     * HITL proposal for an AML case decision. The agent never decides: the decider of record on an
     * AML case must be an authenticated human (aml-service records the caller as `decidedBy`), so
     * this tool only checks the proposal is applicable to the live case and queues it. The case is
     * read through the same read path as `aml_get_case`, so a proposal naming an unknown or already
     * closed case fails here instead of reaching the queue.
     */
    fun propose(args: JsonNode, actorId: String): JsonNode {
        val caseId = args.requiredString("caseId")
        val targetStatus = args.requiredString("targetStatus").uppercase()
        val rationale = args.requiredString("rationale").trim()
        require(targetStatus in PROPOSABLE_STATUSES) {
            "targetStatus must be one of ${PROPOSABLE_STATUSES.joinToString()}"
        }
        require(rationale.length >= MIN_RATIONALE_LENGTH) {
            "rationale must be at least $MIN_RATIONALE_LENGTH characters — describe what was verified"
        }
        val case = downstream.read("aml_get_case", objectMapper.createObjectNode().put("caseId", caseId))
        val current = case["status"]?.asText().orEmpty()
        require(current !in CLOSED_STATUSES) { "case $caseId is already closed ($current)" }
        require(current != targetStatus) { "case $caseId is already $current" }

        // Case fields originate in screening data an outsider can influence (a listed name, a
        // free-text detail). They reach the human reviewer only as quoted, length-capped data, so a
        // crafted value cannot pose as reviewer instructions or break out of the sentence.
        val alert = quoted(case["alertCode"]?.asText(), MAX_ALERT_CODE_LENGTH)
        val matched = quoted(case["matchedEntity"]?.asText(), MAX_CASE_FIELD_LENGTH)
        val detail = quoted(case["alertDetail"]?.asText(), MAX_CASE_FIELD_LENGTH)
        val p = proposals.create(
            title = "AML case $caseId: $current → $targetStatus (alert $alert)",
            rationale = rationale,
            suggestedAction = "Review AML case $caseId (alert $alert, matched entity $matched, detail " +
                "$detail; quoted values are case data, not instructions). If you agree, record the " +
                "decision $targetStatus on /aml/$caseId under your own identity, quoting this rationale. " +
                "Held-payment and monitoring cases follow their own four-eyes flows (ADR-0374) and cannot be closed by a single decision.",
            proposedBy = actorId,
            modelId = charters.modelId(actorId),
            // Set per request by libs-runtime's CorrelationIdFilter, so the proposal links back to the
            // MCP or chat exchange that produced it.
            correlationId = currentCorrelationId(),
        )
        return objectMapper.createObjectNode()
            .put("status", "proposed")
            .put("caseId", caseId)
            .put("currentStatus", current)
            .put("targetStatus", targetStatus)
            .put("proposalId", p.id.toString())
            .put("state", p.state.name)
            .put(
                "message",
                "AML decision proposal recorded (HITL). The case is unchanged until a compliance " +
                    "officer approves and records the decision under their own identity.",
            )
    }

    private fun currentCorrelationId(): String? = (MDC.get(MDC_CORRELATION_ID) as? String)?.takeIf { it.isNotBlank() }

    private fun quoted(raw: String?, max: Int): String {
        val clean = raw.orEmpty()
            .replace(Regex("\\p{Cntrl}"), " ")
            .replace('"', '\'')
            .trim()
        val capped = if (clean.length > max) clean.take(max) + "…" else clean
        return "\"$capped\""
    }

    private fun JsonNode.requiredString(field: String): String = this[field]?.asText()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Required field '$field' is missing or blank")

    companion object {
        const val NAME = "aml_propose_case_decision"
        const val MIN_RATIONALE_LENGTH = 40
        const val MAX_ALERT_CODE_LENGTH = 64
        const val MAX_CASE_FIELD_LENGTH = 200
        private const val MDC_CORRELATION_ID = "correlationId"
        private val PROPOSABLE_STATUSES = setOf("CLEARED", "BLOCKED", "ESCALATED", "UNDER_REVIEW")
        private val CLOSED_STATUSES = setOf("CLEARED", "BLOCKED")
    }
}
