// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.OffsetDateTime

/** Unit coverage for [ApprovalResource.decide] (ADR-0155) — the checker-id resolution and
 *  not-found branch, without booting a full @QuarkusTest. */
class ApprovalResourceTest {

    private fun resourceWith(store: ApprovalStore, principalName: String?): ApprovalResource {
        val resource = ApprovalResource(store)
        val identity = mockk<SecurityIdentity>()
        val principal = principalName?.let {
            mockk<Principal>().also { p -> io.mockk.every { p.name } returns it }
        }
        io.mockk.every { identity.principal } returns principal
        resource.identity = identity
        return resource
    }

    @Test
    fun `decide resolves the checker id from the security identity and returns the mapped response`(): Unit =
        runBlocking {
            val decidedAt = OffsetDateTime.parse("2026-07-12T00:00:00Z")
            val approval = PendingApproval(
                id = "appr-1",
                action = "transaction.reverse",
                resourceId = "txn-1",
                makerId = "maker",
                status = ApprovalStatus.APPROVED,
                createdAt = decidedAt.minusHours(1),
                decidedBy = "checker",
                decidedAt = decidedAt,
            )
            val store = mockk<ApprovalStore>()
            coEvery { store.decide("appr-1", "checker", true) } returns approval

            val response = resourceWith(store, "checker").decide("appr-1", DecideApprovalRequest(approve = true))

            assertThat(response.status).isEqualTo(200)
            val body = response.entity as ApprovalResponse
            assertThat(body.id).isEqualTo("appr-1")
            assertThat(body.status).isEqualTo("APPROVED")
        }

    @Test
    fun `decide falls back to anonymous when the security identity has no principal`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.decide("appr-2", "anonymous", false) } returns null

        assertThatThrownBy {
            runBlocking { resourceWith(store, null).decide("appr-2", DecideApprovalRequest(approve = false)) }
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `decide throws NotFoundException when the store finds no matching pending approval`(): Unit = runBlocking {
        val store = mockk<ApprovalStore>()
        coEvery { store.decide("missing", "checker", true) } returns null

        assertThatThrownBy {
            runBlocking { resourceWith(store, "checker").decide("missing", DecideApprovalRequest(approve = true)) }
        }.isInstanceOf(NotFoundException::class.java)
            .hasMessageContaining("missing")
    }
}
