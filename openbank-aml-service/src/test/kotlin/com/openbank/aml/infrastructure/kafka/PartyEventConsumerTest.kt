// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.application.usecase.AmlCaseService
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [PartyEventConsumer] — covers the PARTY_ERASED path (GDPR Art. 17).
 *
 * The consumer is tested in pure unit style (no Quarkus container) following the same pattern
 * as [com.openbank.aml.application.usecase.AmlCaseServiceTest].
 */
private class TransientDbFailure : RuntimeException("DB unavailable")

class PartyEventConsumerTest {

    private lateinit var amlUseCase: AmlCaseUseCase
    private lateinit var amlCaseRepository: AmlCaseRepository
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
    private lateinit var consumer: PartyEventConsumer

    @BeforeEach
    fun setUp() {
        amlUseCase = mockk(relaxed = true)
        amlCaseRepository = mockk(relaxed = true)
        consumer = PartyEventConsumer(
            amlUseCase = amlUseCase,
            amlCaseRepository = amlCaseRepository,
            objectMapper = objectMapper,
            autoClear = false,
        )
    }

    @Test
    fun `PARTY_ERASED with valid partyId calls anonymizeByPartyId`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { amlCaseRepository.anonymizeByPartyId(partyId) } returns 2

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { amlCaseRepository.anonymizeByPartyId(partyId) }
    }

    @Test
    fun `PARTY_ERASED with missing partyId is skipped without calling repository`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"not-a-uuid"}""")

        coVerify(exactly = 0) { amlCaseRepository.anonymizeByPartyId(any()) }
    }

    @Test
    fun `PARTY_ERASED with absent partyId field is skipped without calling repository`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_ERASED"}""")

        coVerify(exactly = 0) { amlCaseRepository.anonymizeByPartyId(any()) }
    }

    @Test
    fun `PARTY_ERASED repository failure is RETHROWN so the connector dead-letters`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { amlCaseRepository.anonymizeByPartyId(partyId) } throws TransientDbFailure()

        // Replaces a test that asserted the opposite ("message is acked"). Acking a failed erasure
        // leaves the PII in place while the log records the erasure as done (#5698).
        assertThrows<TransientDbFailure> {
            runBlocking { consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""") }
        }

        coVerify(exactly = 3) { amlCaseRepository.anonymizeByPartyId(partyId) }
    }

    @Test
    fun `PARTY_CREATED for INDIVIDUAL still opens an AML case`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume("""{"eventType":"PARTY_CREATED","partyType":"INDIVIDUAL","partyId":"$partyId"}""")

        coVerify(exactly = 1) { amlUseCase.createCase(any()) }
        coVerify(exactly = 0) { amlCaseRepository.anonymizeByPartyId(any()) }
    }

    @Test
    fun `unknown event type is silently ignored`(): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_SUSPENDED","partyId":"${UUID.randomUUID()}"}""")

        coVerify(exactly = 0) { amlUseCase.createCase(any()) }
        coVerify(exactly = 0) { amlCaseRepository.anonymizeByPartyId(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["COMPANY", "SOLE_TRADER"])
    fun `PARTY_CREATED for a business party opens an onboarding AML case`(partyType: String): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        consumer.consume("""{"eventType":"PARTY_CREATED","partyType":"$partyType","partyId":"$partyId"}""")

        coVerify(exactly = 1) {
            amlUseCase.createCase(
                match {
                    it.partyId == partyId && it.idempotencyKey == "$partyId:CUSTOMER_ONBOARDING"
                },
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["TRUST", "UNKNOWN", ""])
    fun `PARTY_CREATED for an unhandled party type is ignored`(partyType: String): Unit = runBlocking {
        consumer.consume("""{"eventType":"PARTY_CREATED","partyType":"$partyType","partyId":"${UUID.randomUUID()}"}""")

        coVerify(exactly = 0) { amlUseCase.createCase(any()) }
    }

    /**
     * End to end through the REAL [AmlCaseService] (only the repository is mocked): with auto-clear on,
     * every screened party type ends CLEARED and writes the same aml.case.status_changed.v1 outbox
     * message, keyed by party id, that party-service's KycAmlEventConsumer turns into AmlStatus.CLEARED.
     */
    @ParameterizedTest
    @ValueSource(strings = ["INDIVIDUAL", "COMPANY", "SOLE_TRADER"])
    fun `auto-clear ends a screened party CLEARED and emits the same status-changed event`(partyType: String): Unit =
        runBlocking {
            val partyId = UUID.randomUUID()
            val stored = mutableMapOf<UUID, AmlCase>()
            val outbox = mutableListOf<OutboxMessage>()
            val repo = mockk<AmlCaseRepository>()
            coEvery { repo.findByIdempotencyKey(any()) } answers
                { stored.values.firstOrNull { it.idempotencyKey == firstArg() } }
            coEvery { repo.findById(any()) } answers { stored[firstArg()] }
            val saved = slot<AmlCase>()
            val msg = slot<OutboxMessage>()
            coEvery { repo.save(capture(saved), capture(msg)) } answers {
                stored[saved.captured.id] = saved.captured
                outbox += msg.captured
                saved.captured
            }
            coEvery { repo.update(capture(saved), capture(msg)) } answers {
                stored[saved.captured.id] = saved.captured
                outbox += msg.captured
                saved.captured
            }
            val clock = Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC)
            val autoClearing = PartyEventConsumer(
                amlUseCase = AmlCaseService(repo, objectMapper, clock),
                amlCaseRepository = repo,
                objectMapper = objectMapper,
                autoClear = true,
            )

            autoClearing.consume("""{"eventType":"PARTY_CREATED","partyType":"$partyType","partyId":"$partyId"}""")

            assertThat(stored.values.single().status).isEqualTo(AmlCaseStatus.CLEARED)
            assertThat(outbox.map { it.eventType })
                .containsExactly(AmlCaseService.EVENT_CASE_CREATED, AmlCaseService.EVENT_STATUS_CHANGED)
            val changed = objectMapper.readTree(outbox.last().payload)
            assertThat(changed.path("partyId").asText()).isEqualTo(partyId.toString())
            assertThat(changed.path("newStatus").asText()).isEqualTo("CLEARED")
        }

    @Test
    fun `without auto-clear a business party case stays OPEN awaiting an analyst`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val saved = slot<AmlCase>()
        val repo = mockk<AmlCaseRepository>()
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.save(capture(saved), any()) } answers { saved.captured }
        val manual = PartyEventConsumer(
            amlUseCase = AmlCaseService(repo, objectMapper, Clock.systemUTC()),
            amlCaseRepository = repo,
            objectMapper = objectMapper,
            autoClear = false,
        )

        manual.consume("""{"eventType":"PARTY_CREATED","partyType":"COMPANY","partyId":"$partyId"}""")

        assertThat(saved.captured.status).isEqualTo(AmlCaseStatus.OPEN)
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }
}
