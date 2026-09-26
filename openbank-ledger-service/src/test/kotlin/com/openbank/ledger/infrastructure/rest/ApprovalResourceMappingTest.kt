// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.approval.web.ApprovalResponse
import com.openbank.libs.approval.web.DecideApprovalRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.OffsetDateTime

/**
 * [ApprovalResource] delegates the shared body (limit clamping, null-body 400, unknown-id 404,
 * self-approval propagation, wire DTOs) to `ApprovalEndpointSupport` (libs-runtime, tested by its
 * own `ApprovalEndpointSupportTest` against a real `InMemoryApprovalStore`, #10915). What THIS
 * test covers is the resource's own responsibility: resolving the checker's identity from
 * [SecurityIdentity] rather than the request body, and wiring ledger's roles/annotations.
 */
class ApprovalResourceMappingTest {

    private fun pendingApproval() = PendingApproval(
        id = "appr-1",
        action = "ledger.reverse",
        resourceId = "journal-1",
        makerId = "maker",
        status = ApprovalStatus.PENDING,
        createdAt = OffsetDateTime.parse("2026-08-19T00:00:00Z"),
    )

    private fun resourceWith(store: ApprovalStore, principalName: String?): ApprovalResource {
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns principalName?.let { name -> Principal { name } }
        val resource = ApprovalResource(store)
        resource.identity = identity
        return resource
    }

    @Test
    fun `listPending exposes the mapped checker queue`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(50) } returns listOf(pendingApproval())

        val response = resourceWith(store, "checker-1").listPending(50)

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as List<ApprovalResponse>
        assertThat(body).hasSize(1)
        assertThat(body.single().makerId).isEqualTo("maker")
        assertThat(body.single().createdAt).isEqualTo("2026-08-19T00:00Z")
    }

    @Test
    fun `listPending clamps a caller-controlled limit`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(200) } returns emptyList()

        resourceWith(store, "checker-1").listPending(10_000)

        coVerify(exactly = 1) { store.findPending(200) }
    }

    @Test
    fun `decide passes the authenticated principal name as checker, never a body field`(): Unit = runBlocking {
        val decided = pendingApproval().copy(status = ApprovalStatus.APPROVED, decidedBy = "checker-1")
        val store = mockk<ApprovalStore> {
            coEvery { decide("appr-1", "checker-1", true) } returns decided
        }

        val response = resourceWith(store, "checker-1").decide("appr-1", DecideApprovalRequest(approve = true))

        assertThat(response.status).isEqualTo(200)
        val body = response.entity as ApprovalResponse
        assertThat(body.status).isEqualTo("APPROVED")
        assertThat(body.decidedBy).isEqualTo("checker-1")
    }

    @Test
    fun `a missing or already-consumed approval id is a 404, not a 200 with a null body`(): Unit = runBlocking {
        val store = mockk<ApprovalStore> {
            coEvery { decide("does-not-exist", any(), any()) } returns null
        }

        assertThatThrownBy {
            runBlocking {
                resourceWith(store, "checker-1").decide("does-not-exist", DecideApprovalRequest(approve = false))
            }
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `an unauthenticated identity resolves to a literal anonymous id, not null or a crash`(): Unit = runBlocking {
        val decided = pendingApproval().copy(status = ApprovalStatus.APPROVED, decidedBy = "anonymous")
        val store = mockk<ApprovalStore> {
            coEvery { decide("appr-1", "anonymous", true) } returns decided
        }

        val response = resourceWith(store, principalName = null).decide("appr-1", DecideApprovalRequest(approve = true))

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `the maker cannot approve their own request through this endpoint`() {
        val maker = "maker"
        val guarding = mockk<ApprovalStore> {
            coEvery { decide("appr-1", maker, any()) } throws SelfApprovalNotAllowedException(maker)
        }

        assertThatThrownBy {
            runBlocking { resourceWith(guarding, maker).decide("appr-1", DecideApprovalRequest(approve = true)) }
        }.isInstanceOf(SelfApprovalNotAllowedException::class.java)
    }
}
