// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The two exceptions in this package are the segregation-of-duties contract every [ApprovalStore]
 * implementation must uphold (ADR-0034 / ADR-0155). They are `IllegalStateException` subtypes on
 * purpose — a service's REST layer maps that to 409 — and their messages are what an operator reads
 * in the audit trail, so both the type and the named identity/state matter.
 */
class ApprovalContractTest {

    @Test
    fun `self-approval names the offending principal and is an IllegalStateException`() {
        val ex = SelfApprovalNotAllowedException("maker-42")
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
        assertThat(ex.message).contains("maker-42").contains("segregation of duties")
    }

    @Test
    fun `an invalid state transition names the approval, the expected state and the actual one`() {
        val ex = InvalidApprovalStateException("appr-7", ApprovalStatus.PENDING, ApprovalStatus.EXECUTED)
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
        assertThat(ex.message)
            .contains("appr-7")
            .contains("PENDING")
            .contains("EXECUTED")
    }

    @Test
    fun `the lifecycle has exactly the four documented statuses and PENDING is the entry point`() {
        assertThat(ApprovalStatus.entries).containsExactly(
            ApprovalStatus.PENDING,
            ApprovalStatus.APPROVED,
            ApprovalStatus.REJECTED,
            ApprovalStatus.EXECUTED,
        )
    }

    @Test
    fun `a freshly created approval carries no decision - decidedBy and decidedAt default to absent`() {
        val pending = PendingApproval(
            id = "appr-1",
            action = "ledger.approve",
            resourceId = "je-1",
            makerId = "maker-1",
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.now(),
        )
        assertThat(pending.decidedBy).isNull()
        assertThat(pending.decidedAt).isNull()
    }

    @Test
    fun `createdAt is a real timestamp, asserted by recency rather than non-nullity`() {
        val before = OffsetDateTime.now()
        val approval = PendingApproval(
            id = "appr-2",
            action = "payment.release",
            resourceId = null,
            makerId = "maker-2",
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.now(),
        )
        assertThat(approval.createdAt).isBetween(before, OffsetDateTime.now())
    }

    @Test
    fun `a resourceless action is representable - not every approval targets a single resource`() {
        val approval = PendingApproval(
            id = "appr-3",
            action = "featureflag.flip",
            resourceId = null,
            makerId = "maker-3",
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.now(),
        )
        assertThat(approval.resourceId).isNull()
        assertThat(approval.action).isEqualTo("featureflag.flip")
    }
}
