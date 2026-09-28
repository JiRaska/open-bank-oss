// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class ApprovalStoreTest {

    private val now = OffsetDateTime.parse("2026-06-06T10:00:00Z")

    private fun approval(
        status: ApprovalStatus = ApprovalStatus.PENDING,
        decidedBy: String? = null,
        decidedAt: OffsetDateTime? = null,
    ) = PendingApproval(
        id = "appr-1",
        action = "sepa.payment.execute",
        resourceId = "payment-42",
        makerId = "alice",
        status = status,
        createdAt = now,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
    )

    @Test
    fun `PendingApproval equality is structural, not identity`() {
        assertThat(approval()).isEqualTo(approval())
        assertThat(approval()).isEqualTo(approval().copy())
    }

    @Test
    fun `PendingApproval differs when the deciding principal differs`() {
        val decidedByBob = approval(status = ApprovalStatus.APPROVED, decidedBy = "bob", decidedAt = now)
        val decidedByCarol = approval(status = ApprovalStatus.APPROVED, decidedBy = "carol", decidedAt = now)
        assertThat(decidedByBob).isNotEqualTo(decidedByCarol)
    }

    @Test
    fun `decidedBy and decidedAt default to null for a freshly created approval`() {
        val fresh = approval()
        assertThat(fresh.decidedBy).isNull()
        assertThat(fresh.decidedAt).isNull()
        assertThat(fresh.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `ApprovalStatus has exactly the four maker-checker lifecycle states, in order`() {
        assertThat(ApprovalStatus.entries.map { it.name })
            .containsExactly("PENDING", "APPROVED", "REJECTED", "EXECUTED")
    }

    @Test
    fun `SelfApprovalNotAllowedException names the offending maker and the reason`() {
        val ex = SelfApprovalNotAllowedException("alice")
        assertThat(ex.message)
            .contains("alice")
            .contains("segregation of duties")
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `InvalidApprovalStateException names the approval, the expected state and the actual state`() {
        val ex = InvalidApprovalStateException(
            id = "appr-1",
            expected = ApprovalStatus.PENDING,
            actual = ApprovalStatus.EXECUTED,
        )
        assertThat(ex.message)
            .contains("appr-1")
            .contains("PENDING")
            .contains("EXECUTED")
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `InvalidApprovalStateException message distinguishes different expected-actual pairs`() {
        val rejectedInsteadOfPending =
            InvalidApprovalStateException("appr-2", ApprovalStatus.PENDING, ApprovalStatus.REJECTED)
        val approvedInsteadOfPending =
            InvalidApprovalStateException("appr-2", ApprovalStatus.PENDING, ApprovalStatus.APPROVED)
        assertThat(rejectedInsteadOfPending.message).isNotEqualTo(approvedInsteadOfPending.message)
    }
}
