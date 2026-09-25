// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.LendingAssignedCandidatesResult
import com.openbank.lending.application.port.out.LendingGraphAccessDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ContextLendingGraphAccessClientTest {
    private val client = mockk<ContextLendingGraphAccessClient>()
    private val adapter = RestLendingGraphAccessAdapter(client)
    private val loanId = UUID.randomUUID()
    private val bearer = "Bearer investigator-token"

    @Test
    fun `only 204 grants and caller identity and exact investigation context are forwarded`() {
        val parameters = ContextLendingGraphAccessClient::class.java
            .getMethod("check", UUID::class.java, String::class.java, String::class.java, String::class.java)
            .parameterAnnotations
        assertThat(parameters[2].filterIsInstance<HeaderParam>().single().value)
            .isEqualTo("X-Investigation-Case-Id")
        assertThat(parameters[3].filterIsInstance<HeaderParam>().single().value)
            .isEqualTo("X-Investigation-Purpose")
        respond(204)

        assertThat(runBlocking { adapter.check(loanId, bearer) }).isEqualTo(LendingGraphAccessDecision.ALLOWED)
        verify(exactly = 1) {
            client.check(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW")
        }
    }

    @Test
    fun `401 403 and 404 refuse with or without the default REST client exception mapper`() {
        for (status in listOf(401, 403, 404)) {
            respond(status)
            assertThat(runBlocking { adapter.check(loanId, bearer) }).isEqualTo(LendingGraphAccessDecision.DENIED)

            every { client.check(loanId, bearer, loanId.toString(), RestLendingGraphAccessAdapter.PURPOSE) } returns
                Uni.createFrom().failure(WebApplicationException(status))
            assertThat(runBlocking { adapter.check(loanId, bearer) }).isEqualTo(LendingGraphAccessDecision.DENIED)
        }
    }

    @Test
    fun `other status and transport failure are unavailable and fail closed`() {
        for (status in listOf(200, 302, 500)) {
            respond(status)
            assertThat(runBlocking { adapter.check(loanId, bearer) }).isEqualTo(LendingGraphAccessDecision.UNAVAILABLE)
        }

        every { client.check(loanId, bearer, loanId.toString(), RestLendingGraphAccessAdapter.PURPOSE) } returns
            Uni.createFrom().failure(IllegalStateException("context offline"))
        assertThat(runBlocking { adapter.check(loanId, bearer) }).isEqualTo(LendingGraphAccessDecision.UNAVAILABLE)
    }

    @Test
    fun `assigned candidates use same bearer and reject malformed or denied lists`() {
        val candidate = UUID.randomUUID()
        val response = mockk<Response>()
        every { response.status } returns 200
        every { response.readEntity(ContextAssignedCandidates::class.java) } returns
            ContextAssignedCandidates(listOf(candidate), true)
        every { response.close() } returns Unit
        every {
            client.assignedCandidates(loanId, bearer, loanId.toString(), RestLendingGraphAccessAdapter.PURPOSE)
        } returns Uni.createFrom().item(response)
        assertThat(runBlocking { adapter.assignedCandidates(loanId, bearer) })
            .isEqualTo(LendingAssignedCandidatesResult.Available(listOf(candidate), true))
        verify(exactly = 1) {
            client.assignedCandidates(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW")
        }

        every { response.readEntity(ContextAssignedCandidates::class.java) } returns
            ContextAssignedCandidates(listOf(loanId), false)
        assertThat(runBlocking { adapter.assignedCandidates(loanId, bearer) })
            .isEqualTo(LendingAssignedCandidatesResult.Unavailable)
        every { response.readEntity(ContextAssignedCandidates::class.java) } returns
            ContextAssignedCandidates(listOf(candidate, candidate), false)
        assertThat(runBlocking { adapter.assignedCandidates(loanId, bearer) })
            .isEqualTo(LendingAssignedCandidatesResult.Unavailable)
        every { response.readEntity(ContextAssignedCandidates::class.java) } returns
            ContextAssignedCandidates(List(257) { UUID.randomUUID() }, true)
        assertThat(runBlocking { adapter.assignedCandidates(loanId, bearer) })
            .isEqualTo(LendingAssignedCandidatesResult.Unavailable)
        every { response.status } returns 403
        assertThat(runBlocking { adapter.assignedCandidates(loanId, bearer) })
            .isEqualTo(LendingAssignedCandidatesResult.Denied)
    }

    private fun respond(status: Int) {
        val response = mockk<Response>()
        every { response.status } returns status
        every { response.close() } returns Unit
        every { client.check(loanId, bearer, loanId.toString(), RestLendingGraphAccessAdapter.PURPOSE) } returns
            Uni.createFrom().item(response)
    }
}
