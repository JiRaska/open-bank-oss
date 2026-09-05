// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.rest

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import io.mockk.coEvery
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
 * [ApprovalResource] delegates the actual self-approval / not-found / state-machine rules to
 * [ApprovalStore] (unit-tested by ApprovalStoreContractTest in openbank-libs-runtime (#3349)) — what THIS test covers is the
 * resource's own two responsibilities: resolving the checker's identity from
 * [SecurityIdentity] rather than the request body (same anti-spoofing property
 * [DispatchControlResourceTest] guards for the maker side), and translating a `null` decide
 * result into 404.
 */
class ApprovalResourceTest {

    private fun approval(status: ApprovalStatus = ApprovalStatus.APPROVED, decidedBy: String? = "checker-1") =
        PendingApproval(
            id = "approval-1",
            action = "opsmessage.compose",
            resourceId = null,
            makerId = "operator-1",
            status = status,
            createdAt = OffsetDateTime.now(),
            decidedBy = decidedBy,
        )

    private fun resourceWith(store: ApprovalStore, principalName: String?): ApprovalResource {
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns principalName?.let { name -> Principal { name } }
        val resource = ApprovalResource(store)
        resource.identity = identity
        return resource
    }

    @Test
    fun `listPending returns the store's pending queue, oldest first, mapped to the response shape`(): Unit =
        runBlocking {
            val store = mockk<ApprovalStore>()
            coEvery { store.findPending(50) } returns listOf(approval())
            val resource = resourceWith(store, "checker-1")

            val response = resource.listPending(50)

            assertThat(response.status).isEqualTo(200)
            @Suppress("UNCHECKED_CAST")
            val body = response.entity as List<ApprovalResponse>
            assertThat(body).hasSize(1)
            assertThat(body[0].id).isEqualTo("approval-1")
            assertThat(body[0].action).isEqualTo("opsmessage.compose")
        }

    @Test
    fun `listPending clamps an out-of-range limit into the 1 to 200 window`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(200) } returns emptyList()

        resourceWith(store, "checker-1").listPending(500)

        io.mockk.coVerify { store.findPending(200) }
    }

    @Test
    fun `decide passes the authenticated principal name as checker, never a body field`(): Unit = runBlocking {
        val store = mockk<ApprovalStore> {
            coEvery { decide("approval-1", "checker-1", true) } returns approval()
        }
        val resource = resourceWith(store, "checker-1")

        val response = resource.decide("approval-1", DecideApprovalRequest(approve = true))

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
        val resource = resourceWith(store, "checker-1")

        assertThatThrownBy {
            runBlocking { resource.decide("does-not-exist", DecideApprovalRequest(approve = false)) }
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `an unauthenticated identity resolves to a literal anonymous id, not null or a crash`(): Unit = runBlocking {
        val store = mockk<ApprovalStore> {
            coEvery { decide("approval-1", "anonymous", true) } returns approval(decidedBy = "anonymous")
        }
        val resource = resourceWith(store, principalName = null)

        val response = resource.decide("approval-1", DecideApprovalRequest(approve = true))

        assertThat(response.status).isEqualTo(200)
    }
}
