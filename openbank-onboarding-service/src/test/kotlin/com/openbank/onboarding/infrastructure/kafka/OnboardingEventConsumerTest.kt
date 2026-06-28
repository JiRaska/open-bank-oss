// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.onboarding.application.usecase.OnboardingProjectionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * Unit tests for [OnboardingEventConsumer] focusing on the PARTY_ERASED / GDPR Art. 17 path.
 *
 * The consumer is constructed directly with mocked dependencies — no Quarkus CDI context needed.
 */
class OnboardingEventConsumerTest {

    private val projection = mockk<OnboardingProjectionService>()
    private val objectMapper = ObjectMapper()
    private lateinit var consumer: OnboardingEventConsumer

    @BeforeEach
    fun setUp() {
        consumer = OnboardingEventConsumer(Clock.systemUTC()).also {
            it.projection = projection
            it.objectMapper = objectMapper
        }
    }

    // ── PARTY_ERASED ──────────────────────────────────────────────────────────

    @Test
    fun `consumePartyEvent calls eraseParty for PARTY_ERASED with valid partyId`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val payload = """{"eventType":"PARTY_ERASED","partyId":"$partyId"}"""
        coEvery { projection.eraseParty(partyId) } just runs

        consumer.consumePartyEvent(payload)

        coVerify(exactly = 1) { projection.eraseParty(partyId) }
    }

    @Test
    fun `consumePartyEvent logs and skips PARTY_ERASED with missing partyId`(): Unit = runBlocking {
        val payload = """{"eventType":"PARTY_ERASED"}"""

        // Should complete without throwing; no eraseParty call expected
        consumer.consumePartyEvent(payload)

        coVerify(exactly = 0) { projection.eraseParty(any()) }
    }

    @Test
    fun `consumePartyEvent logs and skips PARTY_ERASED with blank partyId`(): Unit = runBlocking {
        val payload = """{"eventType":"PARTY_ERASED","partyId":""}"""

        consumer.consumePartyEvent(payload)

        coVerify(exactly = 0) { projection.eraseParty(any()) }
    }

    @Test
    fun `consumePartyEvent does not call eraseParty for PARTY_CREATED events`(): Unit = runBlocking {
        // PARTY_CREATED routes through applyEvent — eraseParty must not be called
        val partyId = UUID.randomUUID()
        val payload = """{"eventType":"PARTY_CREATED","partyId":"$partyId",""" +
            """"legalName":"Alice","email":"a@b.com","occurredAt":"2026-06-01T10:00:00Z"}"""
        coEvery { projection.applyEvent(any()) } just runs

        consumer.consumePartyEvent(payload)

        coVerify(exactly = 0) { projection.eraseParty(any()) }
    }
}
