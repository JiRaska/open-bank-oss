// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.PendingApproval
import com.openbank.sanctions.infrastructure.rest.toResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Unit coverage for the [PendingApproval] -> `ApprovalResponse` mapping (ADR-0155). */
class ApprovalResourceMappingTest {

    @Test
    fun `toResponse maps every field including a decided approval`() {
        val decidedAt = OffsetDateTime.parse("2026-07-13T00:00:00Z")
        val approval = PendingApproval(
            id = "appr-1",
            action = "sanctions.clear",
            resourceId = "check-1",
            makerId = "maker",
            status = ApprovalStatus.APPROVED,
            createdAt = decidedAt.minusHours(1),
            decidedBy = "checker",
            decidedAt = decidedAt,
        )

        val response = approval.toResponse()

        assertThat(response.id).isEqualTo("appr-1")
        assertThat(response.action).isEqualTo("sanctions.clear")
        assertThat(response.resourceId).isEqualTo("check-1")
        assertThat(response.status).isEqualTo("APPROVED")
        assertThat(response.decidedBy).isEqualTo("checker")
        // makerId and createdAt were added for the checker's queue (#3472). Without them a
        // supervisor sees a list of opaque ids: "who asked" is the thing a second pair of eyes
        // is checking, and age is the only visible sign of a request nearing the 24h TTL.
        assertThat(response.makerId).isEqualTo("maker")
        assertThat(response.createdAt).isEqualTo(decidedAt.minusHours(1).toString())
    }

    @Test
    fun `toResponse maps a still-pending approval with a null decidedBy`() {
        val approval = PendingApproval(
            id = "appr-2",
            action = "sanctions.clear",
            resourceId = null,
            makerId = "maker",
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.parse("2026-07-13T00:00:00Z"),
        )

        val response = approval.toResponse()

        assertThat(response.resourceId).isNull()
        assertThat(response.status).isEqualTo("PENDING")
        assertThat(response.decidedBy).isNull()
    }
}
