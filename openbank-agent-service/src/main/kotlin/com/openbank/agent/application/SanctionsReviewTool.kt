// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.out.SanctionsReviewPort
import com.openbank.agent.domain.ToolDefinition
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * `sanctions_review_check` (capability `sanctions.review.maker`): the agent submits a sanctions
 * hit review as the four-eyes MAKER (ADR-0155). It never completes a review alone — sanctions-service
 * pauses the call (PENDING_APPROVAL + approvalId) until a DIFFERENT, human principal approves it, and
 * the store refuses self-approval. The agent then retries the IDENTICAL request with the approvalId;
 * the approval is bound to the request fingerprint, so a changed status or note mints a new approval.
 *
 * Only a check awaiting review (HIT / POTENTIAL_HIT) may be submitted, and only towards CLEAR — the agent's job is false-positive triage, not confirming a hit.
 */
@ApplicationScoped
class SanctionsReviewTool {

    @Inject lateinit var sanctions: SanctionsReviewPort

    @Inject lateinit var objectMapper: ObjectMapper

    fun review(args: JsonNode, onBehalfOf: String?): JsonNode {
        // Four-eyes needs two PEOPLE: the instructing person is bound to the approval and refused as
        // its checker by sanctions-service. Without a verified person there is nobody to bind (#16381).
        requireNotNull(onBehalfOf?.takeIf { it.isNotBlank() }) {
            "sanctions_review_check needs a signed-in person driving the session; it is unavailable to unattended runs"
        }
        val checkId = args.requiredString("checkId")
        val newStatus = args.requiredString("newStatus").uppercase()
        val note = args.requiredString("note").trim()
        val approvalId = args["approvalId"]?.asText()?.takeIf { it.isNotBlank() }
        require(newStatus in REVIEWABLE_TARGETS) { "newStatus must be one of ${REVIEWABLE_TARGETS.joinToString()}" }
        require(note.length >= MIN_NOTE_LENGTH) {
            "note must be at least $MIN_NOTE_LENGTH characters — state what was compared and why it is not a match"
        }
        val current = sanctions.getCheck(checkId)["status"]?.asText().orEmpty()
        // On the retry the check is still HIT/POTENTIAL_HIT: the paused request changed nothing.
        require(current in AWAITING_REVIEW) { "check $checkId is $current, not awaiting review" }

        val outcome = sanctions.submitReview(checkId, newStatus, note, approvalId, onBehalfOf)
        val result = objectMapper.createObjectNode()
            .put("checkId", checkId)
            .put("newStatus", newStatus)
            .put("httpStatus", outcome.status)
        outcome.body?.let { result.set<JsonNode>("upstream", it) }
        return when {
            outcome.status == HTTP_ACCEPTED ->
                result
                    .put("status", "pending_approval")
                    .put("approvalId", outcome.body?.get("approvalId")?.asText())
                    .put(
                        "message",
                        "Four-eyes: a human OTHER than $onBehalfOf must approve this in the admin-ui " +
                            "sanctions approval queue. Then call sanctions_review_check again with the SAME " +
                            "checkId, newStatus and note plus this approvalId.",
                    )
            outcome.status in HTTP_OK_RANGE -> result.put("status", "reviewed")
            else -> error("sanctions-service refused the review (HTTP ${outcome.status}): ${outcome.body ?: "no body"}")
        }
    }

    private fun JsonNode.requiredString(field: String): String = this[field]?.asText()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Required field '$field' is missing or blank")

    companion object {
        val DEFINITION = ToolDefinition(
            name = "sanctions_review_check",
            description =
            "Submit a sanctions hit review (HIT / POTENTIAL_HIT -> CLEAR only) as the four-eyes MAKER, " +
                "on behalf of the signed-in person driving the session. " +
                "Typical use: a name-only fuzzy match whose DOB/nationality differ. The first call returns " +
                "pending_approval + approvalId and changes nothing; after a DIFFERENT person approves it, call " +
                "again with the SAME checkId, newStatus and note plus the approvalId.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "checkId" to prop("UUID of the sanctions check"),
                    "newStatus" to prop("CLEAR (the only status the agent may submit)"),
                    "note" to prop("What was compared and why it is (not) a match, min 10 chars"),
                    "approvalId" to prop("Approval id from the first call, only on the retry"),
                ),
                "required" to listOf("checkId", "newStatus", "note"),
            ),
        )

        private fun prop(description: String) = mapOf("type" to "string", "description" to description)
        const val NAME = "sanctions_review_check"
        const val MIN_NOTE_LENGTH = 10
        private const val HTTP_ACCEPTED = 202
        private val HTTP_OK_RANGE = 200..299
        private val REVIEWABLE_TARGETS = setOf("CLEAR")
        private val AWAITING_REVIEW = setOf("HIT", "POTENTIAL_HIT")
    }
}
