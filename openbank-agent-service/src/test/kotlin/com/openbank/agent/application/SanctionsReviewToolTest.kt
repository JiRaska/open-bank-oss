// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.out.SanctionsReviewPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The agent is the four-eyes MAKER for a sanctions hit review (ADR-0155): its first submission must
 * surface the upstream's PENDING_APPROVAL + approvalId (and claim nothing was reviewed); the retry
 * must carry that approvalId with the identical status/note; and a check not awaiting review, an
 * attempt to CONFIRM a hit, or a thin note never reaches sanctions-service.
 */
class SanctionsReviewToolTest {

    private val mapper = ObjectMapper()
    private val port = mockk<SanctionsReviewPort>()
    private val tool = SanctionsReviewTool().also {
        it.sanctions = port
        it.objectMapper = mapper
    }
    private val checkId = "6f1c2a8e-0000-4000-8000-000000000001"
    private val note = "Name-only fuzzy match; DOB and nationality differ from the listed entry."

    private fun check(status: String) = mapper.createObjectNode().put("id", checkId).put("status", status)

    private fun args(newStatus: String = "CLEAR", note: String = this.note, approvalId: String? = null) =
        mapper.createObjectNode().put("checkId", checkId).put("newStatus", newStatus).put("note", note)
            .also { node -> approvalId?.let { node.put("approvalId", it) } }

    @Test
    fun `first submission is paused for a human checker and says so`() {
        every { port.getCheck(checkId) } returns check("POTENTIAL_HIT")
        every { port.submitReview(checkId, "CLEAR", note, null) } returns SanctionsReviewPort.Outcome(
            202,
            mapper.createObjectNode().put("status", "PENDING_APPROVAL").put("approvalId", "appr-1"),
        )

        val out = tool.review(args())

        assertThat(out["status"].asText()).isEqualTo("pending_approval")
        assertThat(out["approvalId"].asText()).isEqualTo("appr-1")
        assertThat(out["message"].asText()).contains("human must approve")
    }

    @Test
    fun `retry forwards the approval id and reports the review`() {
        every { port.getCheck(checkId) } returns check("POTENTIAL_HIT")
        every { port.submitReview(checkId, "CLEAR", note, "appr-1") } returns
            SanctionsReviewPort.Outcome(200, check("CLEAR"))

        val out = tool.review(args(approvalId = "appr-1"))

        assertThat(out["status"].asText()).isEqualTo("reviewed")
        verify(exactly = 1) { port.submitReview(checkId, "CLEAR", note, "appr-1") }
    }

    @Test
    fun `an upstream refusal is an error, never a success`() {
        every { port.getCheck(checkId) } returns check("HIT")
        every { port.submitReview(any(), any(), any(), any()) } returns SanctionsReviewPort.Outcome(403, null)
        assertThatThrownBy { tool.review(args()) }.hasMessageContaining("HTTP 403")
    }

    @Test
    fun `confirming a hit, a thin note or a check not awaiting review never reach the service`() {
        every { port.getCheck(checkId) } returns check("CLEAR")
        assertThatThrownBy {
            tool.review(args(newStatus = "WHITELISTED"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            tool.review(args(note = "fp"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { tool.review(args()) }.hasMessageContaining("not awaiting review")
        verify(exactly = 0) { port.submitReview(any(), any(), any(), any()) }
    }
}
