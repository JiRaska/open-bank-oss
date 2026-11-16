// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.rest

import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #16381: the agent identity may submit a review only towards CLEAR.
 * Enforced here, server-side — the agent tool's own check is not a control a compromised
 * agent-service would honour.
 */
class SanctionsResourceV3AgentReviewTest {

    private val useCase = mockk<SanctionsUseCase>()
    private val resource = SanctionsResourceV3(useCase)

    private fun caller(clientId: String, username: String) {
        resource.identity = mockk<SecurityIdentity> {
            every { principal } returns mockk<JsonWebToken> {
                every { name } returns username
                every { subject } returns "subject-$username"
                every { getClaim<Any>("azp") } returns clientId
                every { getClaim<Any>("preferred_username") } returns username
                every { getClaim<Any>("client_id") } returns null
                every { getClaim<Any>("clientId") } returns null
                every { getClaim<String>("azp") } returns clientId
                every { getClaim<String>("preferred_username") } returns username
                every { getClaim<String>("client_id") } returns null
                every { getClaim<String>("clientId") } returns null
                every { claimNames } returns setOf("azp", "preferred_username")
            }
        }
    }

    private fun request(status: SanctionsCheckStatus) =
        ReviewRequest(UUID.randomUUID(), "Name-only fuzzy match; DOB differs.", status)

    @Test
    fun `the agent identity is refused every status except CLEAR`() {
        caller("openbank-agent", "service-account-openbank-agent")
        SanctionsCheckStatus.entries.filter { it != SanctionsCheckStatus.CLEAR }.forEach { status ->
            assertThatThrownBy { runBlocking { resource.review(request(status)) } }
                .describedAs(status.name)
                .isInstanceOf(ForbiddenException::class.java)
        }
        coVerify(exactly = 0) { useCase.review(any()) }
    }

    @Test
    fun `the agent identity may clear, and a human is not limited`() {
        coEvery { useCase.review(any()) } returns mockk(relaxed = true)
        caller("openbank-agent", "service-account-openbank-agent")
        runBlocking { resource.review(request(SanctionsCheckStatus.CLEAR)) }
        caller("openbank-admin-ui", "officer-1")
        runBlocking { resource.review(request(SanctionsCheckStatus.HIT)) }
        coVerify(exactly = 2) { useCase.review(any()) }
    }
}
