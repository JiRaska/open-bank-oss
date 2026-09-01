// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent

import com.openbank.consent.infrastructure.rest.ApprovalResource
import com.openbank.consent.infrastructure.rest.ApprovalResponse
import com.openbank.consent.infrastructure.rest.toResponse
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Unit coverage for the [PendingApproval] -> `ApprovalResponse` mapping (ADR-0155). */
class ApprovalResourceMappingTest {

    @Test
    fun `listPending exposes provenance in the checker queue`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(50) } returns listOf(pendingApproval())

        val response = ApprovalResource(store).listPending(50)

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as List<ApprovalResponse>
        assertThat(body.single().makerId).isEqualTo("maker")
        assertThat(body.single().createdAt).isEqualTo("2026-07-13T00:00Z")
    }

    @Test
    fun `listPending clamps a caller-controlled limit`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(200) } returns emptyList()

        ApprovalResource(store).listPending(10_000)

        coVerify(exactly = 1) { store.findPending(200) }
    }

    @Test
    fun `toResponse maps every field including a decided approval`() {
        val decidedAt = OffsetDateTime.parse("2026-07-13T00:00:00Z")
        val approval = PendingApproval(
            id = "appr-1",
            action = "consent.grant",
            resourceId = "consent-1",
            makerId = "maker",
            status = ApprovalStatus.APPROVED,
            createdAt = decidedAt.minusHours(1),
            decidedBy = "checker",
            decidedAt = decidedAt,
        )

        val response = approval.toResponse()

        assertThat(response.id).isEqualTo("appr-1")
        assertThat(response.action).isEqualTo("consent.grant")
        assertThat(response.resourceId).isEqualTo("consent-1")
        assertThat(response.status).isEqualTo("APPROVED")
        assertThat(response.makerId).isEqualTo("maker")
        assertThat(response.createdAt).isEqualTo("2026-07-12T23:00Z")
        assertThat(response.decidedBy).isEqualTo("checker")
    }

    @Test
    fun `toResponse maps a still-pending approval with a null decidedBy`() {
        val approval = PendingApproval(
            id = "appr-2",
            action = "consent.revoke",
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

    private fun pendingApproval() = PendingApproval(
        id = "appr-pending",
        action = "consent.grant",
        resourceId = "consent-1",
        makerId = "maker",
        status = ApprovalStatus.PENDING,
        createdAt = OffsetDateTime.parse("2026-07-13T00:00:00Z"),
    )
}
