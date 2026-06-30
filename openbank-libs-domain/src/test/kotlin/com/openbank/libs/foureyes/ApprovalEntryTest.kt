// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.foureyes

import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.ProposalState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class ApprovalEntryTest {

    private val now = Instant.parse("2026-06-06T10:00:00Z")
    private val proposalId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun entry(
        state: ProposalState = ProposalState.PROPOSED,
        decidedBy: String? = null,
        decidedAt: Instant? = null,
        decisionReason: String? = null,
        executedAt: Instant? = null,
        ttlExpiry: Instant? = null,
    ) = ApprovalEntry(
        proposalId = proposalId,
        operation = "kyc.case.approve",
        resourceType = "kycCase",
        resourceId = "case-42",
        state = state,
        proposedBy = "alice",
        proposedAt = now,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        executedAt = executedAt,
        payload = """{"action":"approve","caseId":"case-42"}""",
        ttlExpiry = ttlExpiry,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `toProposal round-trips all fields`() {
        val e = entry(
            state = ProposalState.APPROVED,
            decidedBy = "bob",
            decidedAt = now.plusSeconds(60),
            decisionReason = "documents complete",
        )
        val proposal = e.toProposal()

        assertThat(proposal.id).isEqualTo(proposalId.toString())
        assertThat(proposal.proposedBy).isEqualTo("alice")
        assertThat(proposal.state).isEqualTo(ProposalState.APPROVED)
        assertThat(proposal.decidedBy).isEqualTo("bob")
        assertThat(proposal.decisionReason).isEqualTo("documents complete")
    }

    @Test
    fun `four-eyes approve succeeds for a different checker`() {
        val proposal = entry().toProposal().approve(checker = "bob", at = now.plusSeconds(30))
        assertThat(proposal.state).isEqualTo(ProposalState.APPROVED)
        assertThat(proposal.decidedBy).isEqualTo("bob")
    }

    @Test
    fun `four-eyes approve blocks same-user self-approval`() {
        val ex = assertThrows<MakerCheckerViolation> {
            entry().toProposal().approve(checker = "alice", at = now)
        }
        assertThat(ex.message).contains("four-eyes")
    }

    @Test
    fun `four-eyes reject blocks same-user self-rejection`() {
        assertThrows<MakerCheckerViolation> {
            entry().toProposal().reject(checker = "alice", at = now, reason = "invalid")
        }
    }

    @Test
    fun `entry with future ttl_expiry is not expired`() {
        val future = entry(ttlExpiry = now.plusSeconds(3600))
        assertThat(future.ttlExpiry!!.isAfter(now)).isTrue()
    }

    @Test
    fun `entry with past ttl_expiry is expired`() {
        val expired = entry(ttlExpiry = now.minusSeconds(1))
        assertThat(expired.ttlExpiry!!.isBefore(now)).isTrue()
    }

    @Test
    fun `ProposalState label extension maps all states`() {
        ProposalState.entries.forEach { state ->
            assertThat(state.label()).isNotBlank()
        }
        assertThat(ProposalState.PROPOSED.label()).isEqualTo("PENDING_APPROVAL")
        assertThat(ProposalState.EXECUTED.label()).isEqualTo("EXECUTED")
    }

    @Test
    fun `ApprovalEvent subtypes carry shared discriminators`() {
        val proposed = ApprovalEvent.ApprovalProposed(
            proposalId = proposalId,
            operation = "kyc.case.approve",
            resourceType = "kycCase",
            resourceId = "case-42",
            proposedBy = "alice",
            payload = "{}",
            ttlExpiry = null,
            occurredAt = now,
        )
        assertThat(proposed.operation).isEqualTo("kyc.case.approve")
        assertThat(proposed.resourceType).isEqualTo("kycCase")

        val approved = ApprovalEvent.ApprovalApproved(
            proposalId = proposalId,
            operation = "kyc.case.approve",
            resourceType = "kycCase",
            resourceId = "case-42",
            approvedBy = "bob",
            reason = "all checks passed",
            occurredAt = now.plusSeconds(30),
        )
        assertThat(approved.approvedBy).isEqualTo("bob")
        assertThat(approved.reason).isEqualTo("all checks passed")
    }
}
