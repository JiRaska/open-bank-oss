// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.CloseParticipationUseCase
import com.openbank.ledger.application.port.`in`.WaiveParticipationCommand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.security.Principal

/** Direct resource guard tests; HTTP RBAC, OIDC verification and persistence are separate evidence. */
class CloseParticipationResourceIdentityTest {
    private val useCase = mockk<CloseParticipationUseCase>()
    private val resource = CloseParticipationResource(useCase)

    private fun jwt(
        username: String? = "human-operator",
        subject: String? = "stable-human-sub",
        clientId: String? = null,
        legacyClientId: String? = null,
    ): JsonWebToken {
        val token = mockk<JsonWebToken>()
        every { token.name } returns "display-name-different-from-sub"
        every { token.subject } returns subject
        every { token.getClaim<String>("preferred_username") } returns username
        every { token.getClaim<String>("client_id") } returns clientId
        every { token.getClaim<String>("clientId") } returns legacyClientId
        every { token.getClaim<String>("azp") } returns "any-interactive-client"
        return token
    }

    private fun identity(principal: Principal?) {
        val securityIdentity = mockk<SecurityIdentity>()
        every { securityIdentity.principal } returns principal
        resource.identity = securityIdentity
    }

    private fun call(): Unit = runBlocking {
        resource.waive("2026-10-17", "interest", WaiveParticipationRequest("accepted outage"))
        Unit
    }

    private fun rejects(principal: Principal?, status: Int = 403) {
        identity(principal)
        val failure = runCatching { call() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(WebApplicationException::class.java)
        assertThat((failure as WebApplicationException).response.status).isEqualTo(status)
        coVerify(exactly = 0) { useCase.waive(any()) }
    }

    @Test
    fun `service account username is denied even with an operator role outside this guard`() =
        rejects(jwt(username = "service-account-close-client"))

    @Test
    fun `renamed client credentials token is denied`() = rejects(jwt(clientId = "close-client"))

    @Test
    fun `legacy clientId note is denied`() = rejects(jwt(legacyClientId = "close-client"))

    @Test
    fun `non JWT identity cannot waive`() = rejects(Principal { "operator" })

    @Test
    fun `null principal cannot waive`() = rejects(null)

    @Test
    fun `missing username cannot waive`() = rejects(jwt(username = null))

    @Test
    fun `blank username cannot waive`() = rejects(jwt(username = ""))

    @Test
    fun `unreadable claims cannot waive`() {
        val token = jwt()
        every { token.getClaim<String>("preferred_username") } throws IllegalStateException("unreadable")
        rejects(token)
    }

    @Test
    fun `missing human subject is denied instead of falling back to display name`() = rejects(jwt(subject = null), 401)

    @Test
    fun `unreadable human subject is unauthorized`() {
        val token = jwt()
        every { token.subject } throws IllegalStateException("unreadable subject")
        rejects(token, 401)
    }

    @Test
    fun `human waiver retains stable subject and reason instead of username`() {
        identity(jwt())
        var command: WaiveParticipationCommand? = null
        coEvery { useCase.waive(any()) } coAnswers {
            command = firstArg()
            throw ReachedUseCase()
        }
        assertThatThrownBy { call() }.isInstanceOf(ReachedUseCase::class.java)
        assertThat(command?.waivedBy).isEqualTo("stable-human-sub")
        assertThat(command?.reason).isEqualTo("accepted outage")
        coVerify(exactly = 1) { useCase.waive(any()) }
    }

    private class ReachedUseCase : RuntimeException()
}
