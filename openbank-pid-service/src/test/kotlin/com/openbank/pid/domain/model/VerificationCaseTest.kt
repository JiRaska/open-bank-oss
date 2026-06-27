// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Unit tests for the four-eyes [VerificationCase] aggregate state machine (ADR-0030).
 * Pure domain — no framework boot.
 */
class VerificationCaseTest {

    private val candidate: UUID = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-06-20T10:00:00Z")

    private fun openCase(): VerificationCase = VerificationCase.open(
        id = UUID.randomUUID(),
        dedupKey = "RN:abc123",
        trigger = VerificationTrigger.RN_COLLISION,
        applicant = ApplicantSnapshot("Jan", "Novak", LocalDate.of(1976, 5, 6), null, listOf("CZ")),
        blindIndex = "abc123",
        candidatePartyIds = listOf(candidate),
        now = now,
    )

    @Test
    fun `open starts in OPEN with no approvers`() {
        val case = openCase()
        assertThat(case.status).isEqualTo(VerificationCaseStatus.OPEN)
        assertThat(case.firstApprover).isNull()
        assertThat(case.finalVerdict).isNull()
    }

    @Test
    fun `first proposal moves to AWAITING_SECOND_APPROVAL`() {
        val case = openCase().proposeFirst("alice", CaseVerdict.LINK_TO_EXISTING, candidate, "typo in name", now)
        assertThat(case.status).isEqualTo(VerificationCaseStatus.AWAITING_SECOND_APPROVAL)
        assertThat(case.firstApprover).isEqualTo("alice")
        assertThat(case.firstVerdict).isEqualTo(CaseVerdict.LINK_TO_EXISTING)
        assertThat(case.firstLinkPartyId).isEqualTo(candidate)
    }

    @Test
    fun `a distinct concurring second approver decides the case`() {
        val decided = openCase()
            .proposeFirst("alice", CaseVerdict.LINK_TO_EXISTING, candidate, null, now)
            .confirmSecond("bob", CaseVerdict.LINK_TO_EXISTING, candidate, now)
        assertThat(decided.status).isEqualTo(VerificationCaseStatus.DECIDED)
        assertThat(decided.finalVerdict).isEqualTo(CaseVerdict.LINK_TO_EXISTING)
        assertThat(decided.finalLinkPartyId).isEqualTo(candidate)
        assertThat(decided.secondApprover).isEqualTo("bob")
        assertThat(decided.decidedAt).isEqualTo(now)
    }

    @Test
    fun `the same approver cannot cast both votes (four-eyes)`() {
        val awaiting = openCase().proposeFirst("alice", CaseVerdict.DISTINCT_NEW, null, null, now)
        assertThatThrownBy { awaiting.confirmSecond("alice", CaseVerdict.DISTINCT_NEW, null, now) }
            .isInstanceOf(IllegalCaseTransition::class.java)
            .hasMessageContaining("already cast the first vote")
    }

    @Test
    fun `a disagreeing second approver is rejected`() {
        val awaiting = openCase().proposeFirst("alice", CaseVerdict.DISTINCT_NEW, null, null, now)
        assertThatThrownBy { awaiting.confirmSecond("bob", CaseVerdict.LINK_TO_EXISTING, candidate, now) }
            .isInstanceOf(IllegalCaseTransition::class.java)
            .hasMessageContaining("disagrees")
    }

    @Test
    fun `LINK_TO_EXISTING requires a candidate party as the link target`() {
        assertThatThrownBy { openCase().proposeFirst("alice", CaseVerdict.LINK_TO_EXISTING, null, null, now) }
            .isInstanceOf(IllegalCaseTransition::class.java)
            .hasMessageContaining("requires a linkPartyId")
    }

    @Test
    fun `LINK_TO_EXISTING rejects a party that is not a candidate`() {
        assertThatThrownBy {
            openCase().proposeFirst("alice", CaseVerdict.LINK_TO_EXISTING, UUID.randomUUID(), null, now)
        }.isInstanceOf(IllegalCaseTransition::class.java)
            .hasMessageContaining("not a candidate")
    }

    @Test
    fun `reopen clears the first proposal and returns to OPEN`() {
        val reopened = openCase()
            .proposeFirst("alice", CaseVerdict.DISTINCT_NEW, null, "mistaken", now)
            .reopen(now)
        assertThat(reopened.status).isEqualTo(VerificationCaseStatus.OPEN)
        assertThat(reopened.firstApprover).isNull()
        assertThat(reopened.firstVerdict).isNull()
        assertThat(reopened.firstNotes).isNull()
    }

    @Test
    fun `cannot propose twice without confirmation`() {
        val awaiting = openCase().proposeFirst("alice", CaseVerdict.DISTINCT_NEW, null, null, now)
        assertThatThrownBy { awaiting.proposeFirst("carol", CaseVerdict.DISTINCT_NEW, null, null, now) }
            .isInstanceOf(IllegalCaseTransition::class.java)
            .hasMessageContaining("not OPEN")
    }

    @Test
    fun `cannot confirm a case that has no awaiting proposal`() {
        assertThatThrownBy { openCase().confirmSecond("bob", CaseVerdict.DISTINCT_NEW, null, now) }
            .isInstanceOf(IllegalCaseTransition::class.java)
            .hasMessageContaining("no first proposal")
    }
}
