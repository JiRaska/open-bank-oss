// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.web

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.InMemoryApprovalStore
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.Principal

class ApprovalEndpointSupportTest {

    private val store = InMemoryApprovalStore()
    private val support = ApprovalEndpointSupport(store)

    private fun identityOf(name: String?): SecurityIdentity =
        mockk<SecurityIdentity> { every { principal } returns name?.let { Principal { it } } }

    @Test
    fun `a maker cannot approve their own request, and the approval stays PENDING`(): Unit = runBlocking {
        val approval = store.create("opsmessage.compose", null, "maker-1")

        assertThatThrownBy {
            runBlocking {
                support.decide(approval.id, DecideApprovalRequest(approve = true), identityOf("maker-1"))
            }
        }.isInstanceOf(SelfApprovalNotAllowedException::class.java)

        assertThat(store.find(approval.id)!!.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `a maker cannot reject their own request either`(): Unit = runBlocking {
        val approval = store.create("opsmessage.compose", null, "maker-1")

        assertThatThrownBy {
            runBlocking {
                support.decide(approval.id, DecideApprovalRequest(approve = false), identityOf("maker-1"))
            }
        }.isInstanceOf(SelfApprovalNotAllowedException::class.java)
    }

    @Test
    fun `a different checker approves, and the response carries the wire shape`(): Unit = runBlocking {
        val approval = store.create("opsmessage.compose", "res-1", "maker-1")

        val response = support.decide(approval.id, DecideApprovalRequest(approve = true), identityOf("checker-1"))

        assertThat(response.status).isEqualTo(200)
        val body = response.entity as ApprovalResponse
        // Every field of PendingApproval -> ApprovalResponse (toApprovalResponse()), pinned here
        // because this is the only mapping test the move to libs-runtime (#11079) left behind —
        // transaction-service's own ApprovalResourceMappingTest asserted `id` and `action` too,
        // and those two were not otherwise covered anywhere in this class.
        assertThat(body.id).isEqualTo(approval.id)
        assertThat(body.action).isEqualTo("opsmessage.compose")
        assertThat(body.status).isEqualTo("APPROVED")
        assertThat(body.decidedBy).isEqualTo("checker-1")
        assertThat(body.makerId).isEqualTo("maker-1")
        assertThat(body.resourceId).isEqualTo("res-1")
        assertThat(body.createdAt).isEqualTo(approval.createdAt.toString())
    }

    @Test
    fun `an unknown id is a 404`() {
        assertThatThrownBy {
            runBlocking { support.decide("nope", DecideApprovalRequest(approve = true), identityOf("checker-1")) }
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `a null body is an IllegalArgumentException (400), not an NPE (500)`() {
        assertThatThrownBy {
            runBlocking { support.decide("any", null, identityOf("checker-1")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a null body is rejected WITHOUT ever resolving the identity`(): Unit = runBlocking {
        // Regression for #11033: the pilot migration (#10917) passed a pre-resolved checker id
        // string (`checkerId(identity)` evaluated at the call site), so `identity` was touched
        // before the null-body check ran at all. That broke consent-service's
        // `ApprovalNullBodyTest`, which constructs a resource with an UNINITIALIZED
        // `SecurityIdentity` specifically to prove a malformed request never reaches
        // authorization-adjacent code. This test pins the fix at the libs-runtime layer: a mock
        // whose `.principal` getter would fail the test if invoked must never be touched when the
        // body is null.
        val untouched = mockk<SecurityIdentity>()

        assertThatThrownBy {
            runBlocking { support.decide("any", null, untouched) }
        }.isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { untouched.principal }
    }

    @Test
    fun `listPending clamps limit into 1 to MAX and returns only PENDING`(): Unit = runBlocking {
        repeat(3) { store.create("a", null, "maker-$it") }
        val decided = store.create("a", null, "maker-x")
        store.decide(decided.id, "checker-1", true)

        @Suppress("UNCHECKED_CAST")
        val all = support.listPending(10_000).entity as List<ApprovalResponse>
        assertThat(all).hasSize(3).allMatch { it.status == "PENDING" }

        @Suppress("UNCHECKED_CAST")
        val one = support.listPending(-5).entity as List<ApprovalResponse>
        assertThat(one).hasSize(1)
    }

    @Test
    fun `checkerId uses principal name and falls back to anonymous`() {
        val named = mockk<SecurityIdentity> { every { principal } returns Principal { "alice" } }
        val none = mockk<SecurityIdentity> { every { principal } returns null }
        assertThat(ApprovalEndpointSupport.checkerId(named)).isEqualTo("alice")
        assertThat(ApprovalEndpointSupport.checkerId(none)).isEqualTo("anonymous")
    }
}
