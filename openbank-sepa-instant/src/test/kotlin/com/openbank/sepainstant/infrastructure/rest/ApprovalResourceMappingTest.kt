// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.PendingApproval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Unit coverage for the [PendingApproval] -> `ApprovalResponse` mapping backing the checker's
 * pending-approvals queue (issue #5679, ADR-0227 D2), mirroring
 * `openbank-sanctions-service`'s, `openbank-ledger-service`'s and
 * `openbank-domestic-payment`'s `ApprovalResourceMappingTest`.
 */
class ApprovalResourceMappingTest {

    @Test
    fun `toResponse maps every field including a decided approval`() {
        val decidedAt = OffsetDateTime.parse("2026-08-19T00:00:00Z")
        val approval = PendingApproval(
            id = "appr-1",
            action = "sctInstPayment.recall",
            resourceId = "payment-1",
            makerId = "maker",
            status = ApprovalStatus.APPROVED,
            createdAt = decidedAt.minusHours(1),
            decidedBy = "checker",
            decidedAt = decidedAt,
        )

        val response = approval.toResponse()

        assertThat(response.id).isEqualTo("appr-1")
        assertThat(response.action).isEqualTo("sctInstPayment.recall")
        assertThat(response.resourceId).isEqualTo("payment-1")
        assertThat(response.status).isEqualTo("APPROVED")
        assertThat(response.decidedBy).isEqualTo("checker")
        // makerId and createdAt were added for the checker's queue (#5679). Without them a
        // supervisor sees a list of opaque ids: "who asked" is the thing a second pair of eyes
        // is checking, and age is the only visible sign of a request nearing the 24h TTL.
        assertThat(response.makerId).isEqualTo("maker")
        assertThat(response.createdAt).isEqualTo(decidedAt.minusHours(1).toString())
    }

    @Test
    fun `toResponse maps a still-pending approval with a null decidedBy`() {
        val approval = PendingApproval(
            id = "appr-2",
            action = "sctInstPayment.recall",
            resourceId = null,
            makerId = "maker",
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.parse("2026-08-19T00:00:00Z"),
        )

        val response = approval.toResponse()

        assertThat(response.resourceId).isNull()
        assertThat(response.status).isEqualTo("PENDING")
        assertThat(response.decidedBy).isNull()
    }
}
