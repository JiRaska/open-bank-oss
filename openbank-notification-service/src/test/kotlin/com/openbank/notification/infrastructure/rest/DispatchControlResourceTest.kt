// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.rest

import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.notification.application.DispatchControlService
import com.openbank.notification.domain.ops.DispatchControlSnapshot
import com.openbank.notification.domain.ops.DispatchState
import com.openbank.notification.domain.ops.ResumeAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.Instant

/**
 * [DispatchControlResource] is the Tier A break-glass control plane for the notification
 * dispatch loop (ADR-0047). Its one hard security property is that the acting identity is
 * ALWAYS taken from the authenticated [SecurityIdentity], never from the request body — so a
 * caller cannot spoof who performed a halt/approve/reject by putting a different actor in the
 * JSON payload. None of the resource's delegation or that anti-spoofing property was tested.
 */
class DispatchControlResourceTest {

    private fun stubSnapshot(state: DispatchState = DispatchState.ENABLED) = DispatchControlSnapshot(
        controlKey = "notification-dispatch",
        state = state,
        version = 1,
        reason = null,
        actor = null,
        effectiveFrom = Instant.EPOCH,
        deferredReviewRequired = false,
    )

    private fun resourceWith(service: DispatchControlService, principalName: String?): DispatchControlResource {
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns principalName?.let { name -> Principal { name } }
        return DispatchControlResource(service, identity)
    }

    @Test
    fun `get returns the current snapshot and recent history from the service`() {
        val service = mockk<DispatchControlService> {
            coEvery { snapshot() } returns stubSnapshot()
            coEvery { history(20) } returns listOf(stubSnapshot(DispatchState.HALTED))
        }
        val resource = resourceWith(service, "operator-1")

        val response = runBlocking { resource.get() }

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body).containsKeys("current", "history")
    }

    @Test
    fun `halt derives the actor from the authenticated identity, never the request body`() {
        val service = mockk<DispatchControlService> {
            coEvery { halt(any(), any()) } returns stubSnapshot(DispatchState.HALTED)
        }
        val resource = resourceWith(service, "operator-1")

        val response = runBlocking {
            resource.halt(DispatchControlResource.ReasonRequest(reason = "incident #123"))
        }

        assertThat(response.status).isEqualTo(200)
        coVerify { service.halt("operator-1", "incident #123") }
    }

    @Test
    fun `halt uses an empty actor when the identity has no principal rather than trusting the body`() {
        val service = mockk<DispatchControlService> {
            coEvery { halt(any(), any()) } returns stubSnapshot(DispatchState.HALTED)
        }
        val resource = resourceWith(service, principalName = null)

        runBlocking { resource.halt(DispatchControlResource.ReasonRequest(reason = "x")) }

        coVerify { service.halt("", "x") }
    }

    @Test
    fun `halt treats a missing reason as blank rather than null`() {
        val service = mockk<DispatchControlService> {
            coEvery { halt(any(), any()) } returns stubSnapshot(DispatchState.HALTED)
        }
        val resource = resourceWith(service, "operator-1")

        runBlocking { resource.halt(DispatchControlResource.ReasonRequest(reason = null)) }

        coVerify { service.halt("operator-1", "") }
    }

    @Test
    fun `proposeResume returns 202 with the proposal id and state`() {
        val proposal = Proposal(
            id = "prop-1",
            action = ResumeAction("notification-dispatch", "back to normal"),
            proposedBy = "operator-1",
            proposedAt = Instant.EPOCH,
        )
        val service = mockk<DispatchControlService> {
            coEvery { proposeResume("operator-1", "back to normal") } returns proposal
        }
        val resource = resourceWith(service, "operator-1")

        val response = runBlocking {
            resource.proposeResume(DispatchControlResource.ReasonRequest(reason = "back to normal"))
        }

        assertThat(response.status).isEqualTo(202)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["proposalId"]).isEqualTo("prop-1")
        assertThat(body["state"]).isEqualTo("PROPOSED")
    }

    @Test
    fun `approveResume delegates the path proposalId and the identity-derived checker to the service`() {
        val service = mockk<DispatchControlService> {
            coEvery { approveResume("prop-1", "checker-2", "looks good") } returns stubSnapshot()
        }
        val resource = resourceWith(service, "checker-2")

        val response = runBlocking {
            resource.approveResume("prop-1", DispatchControlResource.ReasonRequest(reason = "looks good"))
        }

        assertThat(response.status).isEqualTo(200)
        coVerify { service.approveResume("prop-1", "checker-2", "looks good") }
    }

    @Test
    fun `rejectResume delegates and returns the resulting proposal state`() {
        val proposal = Proposal(
            id = "prop-1",
            action = ResumeAction("notification-dispatch", "back to normal"),
            proposedBy = "operator-1",
            proposedAt = Instant.EPOCH,
            state = ProposalState.REJECTED,
            decidedBy = "checker-2",
        )
        val service = mockk<DispatchControlService> {
            coEvery { rejectResume("prop-1", "checker-2", "not yet") } returns proposal
        }
        val resource = resourceWith(service, "checker-2")

        val response = runBlocking {
            resource.rejectResume("prop-1", DispatchControlResource.ReasonRequest(reason = "not yet"))
        }

        assertThat(response.status).isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertThat(body["proposalId"]).isEqualTo("prop-1")
        assertThat(body["state"]).isEqualTo("REJECTED")
    }
}
