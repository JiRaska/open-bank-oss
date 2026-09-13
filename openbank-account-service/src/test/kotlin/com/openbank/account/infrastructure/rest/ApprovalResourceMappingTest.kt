// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

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

class ApprovalResourceMappingTest {

    @Test
    fun `listPending exposes the mapped checker queue`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(50) } returns listOf(pendingApproval())

        val response = ApprovalResource(store).listPending(50)

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as List<ApprovalResponse>
        assertThat(body).hasSize(1)
        assertThat(body.single().makerId).isEqualTo("maker")
        assertThat(body.single().createdAt).isEqualTo("2026-08-23T00:00Z")
    }

    @Test
    fun `listPending clamps a caller-controlled limit`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(200) } returns emptyList()

        ApprovalResource(store).listPending(10_000)

        coVerify(exactly = 1) { store.findPending(200) }
    }

    @Test
    fun `decided response preserves the existing fields and provenance`() {
        val decidedAt = OffsetDateTime.parse("2026-08-23T01:00:00Z")
        val approval = PendingApproval(
            id = "appr-2",
            action = "account.freeze",
            resourceId = "account-1",
            makerId = "maker",
            status = ApprovalStatus.APPROVED,
            createdAt = decidedAt.minusHours(1),
            decidedBy = "checker",
            decidedAt = decidedAt,
        )

        val response = approval.toResponse()

        assertThat(response.id).isEqualTo("appr-2")
        assertThat(response.action).isEqualTo("account.freeze")
        assertThat(response.resourceId).isEqualTo("account-1")
        assertThat(response.status).isEqualTo("APPROVED")
        assertThat(response.makerId).isEqualTo("maker")
        assertThat(response.createdAt).isEqualTo("2026-08-23T00:00Z")
        assertThat(response.decidedBy).isEqualTo("checker")
    }

    private fun pendingApproval() = PendingApproval(
        id = "appr-1",
        action = "account.freeze",
        resourceId = "account-1",
        makerId = "maker",
        status = ApprovalStatus.PENDING,
        createdAt = OffsetDateTime.parse("2026-08-23T00:00:00Z"),
    )
}
