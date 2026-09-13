// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.onboarding.application.usecase.BusinessOnboardingProjectionService
import com.openbank.onboarding.domain.model.BusinessCaseStage
import com.openbank.onboarding.domain.model.BusinessOnboardingEvent
import com.openbank.onboarding.infrastructure.observability.ProjectionOutcomeMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The payloads below are the flat wire shape of `KybEventPayload` (kyb-service, ADR-0284): the
 * outbox publisher sends `entry.payload` verbatim, so every field here is a constructor property
 * of the corresponding data class, not an envelope wrapper. That producer lives in another
 * module, so the JSON cannot be generated from it here — the contract test for the pair is the
 * AsyncAPI document in `openbank-contracts/openbank-kyb-service/asyncapi.yaml`.
 */
class BusinessOnboardingEventConsumerTest {

    private val projection = mockk<BusinessOnboardingProjectionService>()
    private val metrics = mockk<ProjectionOutcomeMetrics>(relaxed = true)
    private lateinit var consumer: BusinessOnboardingEventConsumer

    @BeforeEach
    fun setUp() {
        consumer = BusinessOnboardingEventConsumer(Clock.systemUTC()).also {
            it.projection = projection
            it.objectMapper = ObjectMapper()
            it.metrics = metrics
        }
        coEvery { projection.project(any()) } just runs
    }

    @Test
    fun `projects a registry-verified event`(): Unit = runBlocking {
        val event = slot<BusinessOnboardingEvent>()

        consumer.consume(payload())

        coVerify { projection.project(capture(event)) }
        assertThat(event.captured.caseId).isEqualTo(CASE)
        assertThat(event.captured.status).isEqualTo(BusinessCaseStage.REGISTRY_VERIFIED)
        assertThat(event.captured.legalName).isEqualTo("ACME s.r.o.")
        assertThat(event.captured.requiredSignatures).isEqualTo(2)
        assertThat(event.captured.occurredAt).isEqualTo(Instant.parse("2026-09-05T09:30:00Z"))
        verify { metrics.record("kyb-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED) }
    }

    @Test
    fun `a status this build does not model is dropped, not guessed`(): Unit = runBlocking {
        consumer.consume(payload(status = "SIGNED_BY_NOTARY"))

        coVerify(exactly = 0) { projection.project(any()) }
        verify { metrics.record("kyb-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED) }
    }

    @Test
    fun `an absent optional field is null, not a placeholder`(): Unit = runBlocking {
        val event = slot<BusinessOnboardingEvent>()

        // BUSINESS_ONBOARDING_STARTED has no register facts yet: the identifier is all we have.
        consumer.consume(
            """
            {"eventType":"BUSINESS_ONBOARDING_STARTED","caseId":"$CASE","status":"IDENTIFIER_ENTERED",
             "identifierScheme":"ICO","identifier":"27074358","country":"CZ","legalName":null,
             "legalFormClass":null,"initiatorPartyId":"$INITIATOR","entityPartyId":null,
             "requiredSignatures":null,"signedCount":0,"occurredAt":"2026-09-05T09:00:00Z",
             "actorId":"party:$INITIATOR","actorType":"HUMAN","sourceService":"kyb-service"}
            """.trimIndent(),
        )

        coVerify { projection.project(capture(event)) }
        assertThat(event.captured.legalName).isNull()
        assertThat(event.captured.entityPartyId).isNull()
        assertThat(event.captured.requiredSignatures).isNull()
    }

    @Test
    fun `a poison pill is acked, not rethrown`(): Unit = runBlocking {
        // Replaying unparseable bytes fails identically forever; nacking wedges the partition or
        // fills the DLQ with the same record. A projection failure is the opposite case and IS
        // rethrown — that is the contract this test pins down by contrast.
        assertThatCode { runBlocking { consumer.consume("{ this is not json") } }.doesNotThrowAnyException()
        coVerify(exactly = 0) { projection.project(any()) }
        verify { metrics.record("kyb-events-in", ProjectionOutcomeMetrics.Outcome.FAILED) }
    }

    @Test
    fun `an event with no case id is dropped`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"BUSINESS_ONBOARDING_STARTED","status":"IDENTIFIER_ENTERED"}""")

        coVerify(exactly = 0) { projection.project(any()) }
        verify { metrics.record("kyb-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED) }
    }

    private fun payload(status: String = "REGISTRY_VERIFIED") =
        """
        {"eventType":"BUSINESS_REGISTRY_VERIFIED","caseId":"$CASE","status":"$status",
         "identifierScheme":"ICO","identifier":"27074358","country":"CZ",
         "legalName":"ACME s.r.o.","legalFormClass":"LIMITED_COMPANY",
         "initiatorPartyId":"$INITIATOR","entityPartyId":"$ENTITY",
         "requiredSignatures":2,"signedCount":0,"occurredAt":"2026-09-05T09:30:00Z",
         "actorId":"system:registry","actorType":"SYSTEM","sourceService":"kyb-service"}
        """.trimIndent()

    private companion object {
        val CASE: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val INITIATOR: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val ENTITY: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
