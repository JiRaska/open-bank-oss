// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

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
        val item = body.single()
        assertThat(item.id).isEqualTo("appr-1")
        assertThat(item.makerId).isEqualTo("maker")
    }

    @Test
    fun `listPending clamps a caller-controlled limit`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(200) } returns emptyList()

        ApprovalResource(store).listPending(10_000)

        coVerify(exactly = 1) { store.findPending(200) }
    }

    @Test
    fun `queue response preserves maker and request age`() {
        val approval = pendingApproval()

        val response = approval.toApprovalResponse()

        assertThat(response.id).isEqualTo("appr-1")
        assertThat(response.action).isEqualTo("party.merge")
        assertThat(response.resourceId).isEqualTo("party-1")
        assertThat(response.status).isEqualTo("PENDING")
        assertThat(response.makerId).isEqualTo("maker")
        assertThat(response.createdAt).isEqualTo(approval.createdAt.toString())
        assertThat(response.decidedBy).isNull()
    }

    @Test
    fun `decided response remains backward compatible`() {
        val decidedAt = OffsetDateTime.parse("2026-08-23T01:00:00Z")
        val approval = PendingApproval(
            id = "appr-2",
            action = "party.merge",
            resourceId = null,
            makerId = "maker",
            status = ApprovalStatus.APPROVED,
            createdAt = decidedAt.minusHours(1),
            decidedBy = "checker",
            decidedAt = decidedAt,
        )

        val response = approval.toApprovalResponse()

        assertThat(response.status).isEqualTo("APPROVED")
        assertThat(response.resourceId).isNull()
        assertThat(response.decidedBy).isEqualTo("checker")
    }

    private fun pendingApproval() = PendingApproval(
        id = "appr-1",
        action = "party.merge",
        resourceId = "party-1",
        makerId = "maker",
        status = ApprovalStatus.PENDING,
        createdAt = OffsetDateTime.parse("2026-08-23T00:00:00Z"),
    )
}
