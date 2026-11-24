// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.testing.openapi.OpenApiWireValidator
import com.openbank.sca.application.port.`in`.GetScaUseCase
import com.openbank.sca.application.usecase.ScaChallengeNotFoundException
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.OffsetDateTime
import java.util.UUID

class McpConnectReceiptTest {
    private val getSca = mockk<GetScaUseCase>()
    private val party = UUID.randomUUID()
    private val completedAt = OffsetDateTime.parse("2026-11-23T09:21:00Z")
    private val hash = "ab".repeat(32)
    private val context = DynamicLinkingData(
        amount = null,
        currency = null,
        creditorIban = null,
        creditorName = null,
        reference = null,
        approvalRequestId = "mcp-connect:synthetic-flow-456",
        payloadSha256 = hash.uppercase(),
    )
    private val challenge = ScaChallenge(
        partyId = party,
        purpose = ScaPurpose.MCP_CLIENT_CONNECT,
        method = ScaMethod.PUSH_NOTIFICATION,
        status = ScaStatus.COMPLETED,
        expiresAt = completedAt.plusMinutes(1),
        completedAt = completedAt,
        consumedAt = completedAt.plusSeconds(1),
        dynamicLinkingData = context,
        createdAt = completedAt.minusMinutes(1),
        decidedByPartyId = party,
        decidedByCredentialId = "synthetic-device-credential",
    )

    private fun token(client: String): JsonWebToken = mockk(relaxed = true) {
        every { name } returns "service-account-$client"
        every { subject } returns "synthetic-subject-$client"
        every { getClaim<Any?>("azp") } returns client
        every { getClaim<Any?>("preferred_username") } returns "service-account-$client"
    }

    private fun resource(principal: Principal = token("openbank-delegation")) = McpConnectReceiptResource(
        getSca,
        mockk<SecurityIdentity>().also { every { it.principal } returns principal },
    )

    @Test
    fun `receipt carries only persisted context and same-party consumed evidence and matches OpenAPI`(): Unit =
        runBlocking {
            coEvery { getSca.getChallenge(challenge.id) } returns challenge
            val response = resource().getMcpConnectReceipt(challenge.id)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
            assertThat(response.entity).isEqualTo(
                McpConnectReceipt(
                    challenge.id,
                    party,
                    party,
                    ScaPurpose.MCP_CLIENT_CONNECT,
                    "mcp-connect:synthetic-flow-456",
                    hash,
                    challenge.consumedAt.toString(),
                ),
            )
            val body = jacksonObjectMapper().writeValueAsString(response.entity)
            assertThat(body).doesNotContain("credential", "redirectUrl")
            val violations = OpenApiWireValidator().violations(body, "/api/v1/sca/challenges/{id}/mcp-connect-receipt")
            assertThat(violations).isEmpty()
        }

    @Test
    fun `shared M2M or a principal name without evidence is denied before reading storage`() {
        listOf(
            token("openbank-services"),
            token("openbank-edge"),
            Principal { "service-account-openbank-delegation" },
        ).forEach { principal ->
            assertThatThrownBy { runBlocking { resource(principal).getMcpConnectReceipt(challenge.id) } }
                .isInstanceOf(ForbiddenException::class.java)
        }
        coVerify(exactly = 0) { getSca.getChallenge(any()) }
    }

    @Test
    fun `non-connect unconsumed denied or unrelated rows cannot be presented as receipts`() {
        val rejected = listOf(
            challenge.copy(purpose = ScaPurpose.APPROVAL),
            challenge.copy(consumedAt = null),
            challenge.copy(status = ScaStatus.FAILED),
            challenge.copy(decidedByPartyId = UUID.randomUUID()),
            challenge.copy(decidedByCredentialId = null),
            challenge.copy(onBehalfOfPartyId = UUID.randomUUID()),
            challenge.copy(dynamicLinkingData = null),
            challenge.copy(dynamicLinkingData = context.copy(approvalRequestId = "mcp-proposal:synthetic")),
            challenge.copy(dynamicLinkingData = context.copy(payloadSha256 = "invalid")),
            challenge.copy(id = UUID.randomUUID()),
        )
        for (row in rejected) {
            coEvery { getSca.getChallenge(challenge.id) } returns row
            assertThatThrownBy { runBlocking { resource().getMcpConnectReceipt(challenge.id) } }
                .isInstanceOf(ScaChallengeNotFoundException::class.java)
        }
    }

    @Test
    fun `storage outage must not return any evidence`() {
        coEvery { getSca.getChallenge(challenge.id) } throws IllegalStateException("unavailable")
        assertThatThrownBy { runBlocking { resource().getMcpConnectReceipt(challenge.id) } }
            .isInstanceOf(ServiceUnavailableException::class.java)
    }
}
