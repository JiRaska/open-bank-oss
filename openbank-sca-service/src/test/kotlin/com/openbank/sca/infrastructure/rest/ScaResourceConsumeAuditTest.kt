// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.sca.application.port.`in`.ConsumeScaCommand
import com.openbank.sca.application.port.`in`.ConsumeScaUseCase
import com.openbank.sca.application.usecase.ScaConsumerScopeViolationException
import com.openbank.sca.domain.model.ConsumerScope
import com.openbank.sca.domain.model.ReservedNamespace
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.quarkus.security.identity.SecurityIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.OffsetDateTime
import java.util.UUID

/** ADR-0335 D1 + D3: the resource derives the scope from the principal and audits actor + subject. */
class ScaResourceConsumeAuditTest {
    private val consume = mockk<ConsumeScaUseCase>()
    private val published = mutableListOf<AuditEvent>()
    private val audit = object : AuditEventPublisher {
        override suspend fun publish(event: AuditEvent) {
            published += event
        }
    }
    private val party = UUID.randomUUID()
    private val challengeId = UUID.randomUUID()

    /** A verified service-account token: azp bound to `service-account-<azp>` (ServiceAccountIdentity). */
    private fun machine(principal: String): Principal = mockk<JsonWebToken>(relaxed = true) {
        every { name } returns principal
        every { subject } returns "sub-$principal"
        every { getClaim<Any?>("azp") } returns principal.removePrefix("service-account-")
        every { getClaim<Any?>("preferred_username") } returns principal
    }

    private fun resource(principal: String, token: Principal = machine(principal)) = ScaResource(
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), consume, mockk(), mockk(),
    ).also { r ->
        r.identity = mockk<SecurityIdentity>().also { every { it.principal } returns token }
        r.auditPublisher = audit
    }

    /**
     * Review of #16672: the reservation follows the VERIFIED machine identity, not the display name.
     * A plain principal (no JWT) and a person's token that merely carries the name are General.
     */
    @Test
    fun `a principal merely NAMED like a scoped consumer gets no reservation`(): Unit = runBlocking {
        val command = slot<ConsumeScaCommand>()
        coEvery { consume.consume(capture(command)) } returns spent()
        val name = "service-account-openbank-pension"
        val person = mockk<JsonWebToken>(relaxed = true) {
            every { this@mockk.name } returns name
            every { subject } returns "a-person"
            every { getClaim<Any?>("azp") } returns "openbank-app"
            every { getClaim<Any?>("preferred_username") } returns "alice"
        }
        listOf(Principal { name }, person).forEach { token ->
            resource(name, token).consume(challengeId, ConsumeScaRequest(partyId = party))
            assertThat(command.captured.scope).isEqualTo(ConsumerScope.General)
        }
    }

    @Test
    fun `a pension consume is scoped from the principal and audited with actor and subject`(): Unit = runBlocking {
        val command = slot<ConsumeScaCommand>()
        coEvery { consume.consume(capture(command)) } returns spent()

        resource("service-account-openbank-pension").consume(challengeId, ConsumeScaRequest(partyId = party))

        assertThat(command.captured.scope).isEqualTo(ConsumerScope.Reserved(ReservedNamespace.PENSION))
        val event = published.single()
        assertThat(event.actorId).isEqualTo("service-account-openbank-pension")
        assertThat(event.resourceId).isEqualTo(party.toString())
        assertThat(event.operation).isEqualTo("scaChallenge.consume")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload["challengeId"]).isEqualTo(challengeId.toString())
    }

    @Test
    fun `a scope refusal is audited as DENIED and still refused`(): Unit = runBlocking {
        coEvery { consume.consume(any()) } throws ScaConsumerScopeViolationException(challengeId)

        assertThatThrownBy {
            runBlocking {
                resource("service-account-openbank-services").consume(challengeId, ConsumeScaRequest(partyId = party))
            }
        }.isInstanceOf(ScaConsumerScopeViolationException::class.java)

        val event = published.single()
        assertThat(event.actorId).isEqualTo("service-account-openbank-services")
        assertThat(event.resourceId).isEqualTo(party.toString())
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
    }

    private fun spent() = ScaChallenge(
        id = challengeId,
        partyId = party,
        purpose = ScaPurpose.APPROVAL,
        method = ScaMethod.PUSH_NOTIFICATION,
        status = ScaStatus.COMPLETED,
        expiresAt = OffsetDateTime.now().plusMinutes(5),
        consumedAt = OffsetDateTime.now(),
        createdAt = OffsetDateTime.now(),
    )
}
