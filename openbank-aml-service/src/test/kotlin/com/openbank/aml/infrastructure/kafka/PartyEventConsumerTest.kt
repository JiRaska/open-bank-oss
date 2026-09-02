// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.out.AmlCaseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
}
