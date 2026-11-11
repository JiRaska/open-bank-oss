// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.report

import com.openbank.libs.governance.MakerCheckerViolation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SuspiciousTransactionReportTest {
    private val t0 = Instant.parse("2026-05-04T08:00:00Z")
    private val outcome = OutcomeLabel("aml.decision-outcomes", 1, "a".repeat(64), "CONFIRMED_SUSPICION")

    private fun draft() = SuspiciousTransactionReport.draft(
        id = UUID.fromString("00000000-0000-4000-8000-00000000f001"),
        partyId = UUID.fromString("00000000-0000-4000-8000-00000000f002"),
        narrative = "  synthetic narrative  ",
        caseIds = listOf(UUID.fromString("00000000-0000-4000-8000-00000000f003")),
        movements = emptyList(),
        draftedBy = "alice",
        at = t0,
    )

    @Test
    fun `the drafter cannot approve their own report`() {
        assertThatThrownBy { draft().approve("alice", t0.plusSeconds(60)) }.isInstanceOf(MakerCheckerViolation::class.java)
    }

    @Test
    fun `a draft cannot be filed, only an approved report can`() {
        assertThatThrownBy { draft().recordFiling("REF-1", t0.plusSeconds(60), "bob", t0.plusSeconds(120), outcome) }
            .isInstanceOf(ReportStateViolation::class.java)
    }

    @Test
    fun `filing records the reference, when it happened, who recorded it and the outcome`() {
        val approved = draft().approve("bob", t0.plusSeconds(60))
        assertThat(approved.state).isEqualTo(ReportState.APPROVED)
        assertThat(approved.approvedBy).isEqualTo("bob")
        assertThat(approved.narrative).isEqualTo("synthetic narrative")

        val filed = approved.recordFiling(" REF-1 ", t0.plusSeconds(600), "carol", t0.plusSeconds(900), outcome)
        assertThat(filed.state).isEqualTo(ReportState.FILED)
        assertThat(filed.filingReference).isEqualTo("REF-1")
        assertThat(filed.filedAt).isEqualTo(t0.plusSeconds(600))
        assertThat(filed.filingRecordedBy).isEqualTo("carol")
        assertThat(filed.filingRecordedAt).isEqualTo(t0.plusSeconds(900))
        assertThat(filed.outcome).isEqualTo(outcome)
        assertThat(filed.updatedAt).isEqualTo(t0.plusSeconds(900))
    }

    @Test
    fun `a filing cannot predate the approval or lie in the future`() {
        val approved = draft().approve("bob", t0.plusSeconds(60))
        assertThatThrownBy { approved.recordFiling("REF-1", t0, "carol", t0.plusSeconds(900), outcome) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { approved.recordFiling("REF-1", t0.plusSeconds(1000), "carol", t0.plusSeconds(900), outcome) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { approved.recordFiling(" ", t0.plusSeconds(600), "carol", t0.plusSeconds(900), outcome) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a draft or an approved report can be withdrawn with an outcome, a closed one cannot`() {
        val withdrawn = draft().withdraw("alice", t0.plusSeconds(60), outcome)
        assertThat(withdrawn.state).isEqualTo(ReportState.WITHDRAWN)
        assertThat(withdrawn.outcome).isEqualTo(outcome)
        assertThatThrownBy { withdrawn.withdraw("alice", t0.plusSeconds(90), outcome) }
            .isInstanceOf(ReportStateViolation::class.java)

        val filed = draft().approve("bob", t0.plusSeconds(60))
            .recordFiling("REF-1", t0.plusSeconds(600), "carol", t0.plusSeconds(900), outcome)
        assertThatThrownBy { filed.withdraw("bob", t0.plusSeconds(1000), outcome) }.isInstanceOf(ReportStateViolation::class.java)
        assertThatThrownBy { filed.approve("dave", t0.plusSeconds(1000)) }.isInstanceOf(ReportStateViolation::class.java)
    }

    @Test
    fun `a report rests on at least one case`() {
        assertThatThrownBy {
            SuspiciousTransactionReport.draft(UUID.randomUUID(), UUID.randomUUID(), "n", emptyList(), emptyList(), "alice", t0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
