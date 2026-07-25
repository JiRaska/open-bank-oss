// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.application.port.`in`.MarketingConsentProjectionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** ADR-0205 D4: the Kafka boundary — JSON parsing, granteeId filtering, event-type dispatch. */
class MarketingConsentEventConsumerTest {

    private val projectionUseCase = mockk<MarketingConsentProjectionUseCase>()
    private val consumer = MarketingConsentEventConsumer(projectionUseCase, ObjectMapper())

    private val partyId = UUID.randomUUID()
    private val consentId = UUID.randomUUID()

    private fun granted(granteeId: String = "party-service:marketing-comms") = """
        {"aggregateId":"$consentId","partyId":"$partyId","granteeId":"$granteeId",
         "granteeType":"INTERNAL_SERVICE","scopes":["MARKETING_COMMS_EMAIL"],
         "validTo":"2027-01-01T00:00:00Z","occurredAt":"2026-03-01T12:00:00Z",
         "eventType":"ConsentGranted","version":1}
    """.trimIndent()

    private fun revoked(eventType: String = "ConsentRevoked", granteeId: String = "party-service:marketing-comms") =
        """
        {"aggregateId":"$consentId","partyId":"$partyId","granteeId":"$granteeId",
         "reason":"customer request","occurredAt":"2026-03-01T12:00:00Z","eventType":"$eventType","version":1}
        """.trimIndent()

    @Test
    fun `consume applies a ConsentGranted for the marketing granteeId`(): Unit = runBlocking {
        coEvery { projectionUseCase.applyGranted(any(), any(), any()) } returns Unit

        consumer.consume(granted())

        coVerify(exactly = 1) {
            projectionUseCase.applyGranted(partyId, consentId, Instant.parse("2026-03-01T12:00:00Z"))
        }
    }

    @Test
    fun `consume ignores a ConsentGranted for any other granteeId — a TPP consent, not ours`(): Unit = runBlocking {
        consumer.consume(granted(granteeId = "some-tpp-eidas-org-id"))

        coVerify(exactly = 0) { projectionUseCase.applyGranted(any(), any(), any()) }
    }

    @Test
    fun `consume applies a ConsentRevoked for the marketing granteeId`(): Unit = runBlocking {
        coEvery { projectionUseCase.applyRevokedOrExpired(any(), any(), any()) } returns true

        consumer.consume(revoked())

        coVerify(exactly = 1) {
            projectionUseCase.applyRevokedOrExpired(partyId, consentId, Instant.parse("2026-03-01T12:00:00Z"))
        }
    }

    @Test
    fun `consume applies a ConsentExpired the same way as a ConsentRevoked`(): Unit = runBlocking {
        coEvery { projectionUseCase.applyRevokedOrExpired(any(), any(), any()) } returns true

        consumer.consume(revoked(eventType = "ConsentExpired"))

        coVerify(exactly = 1) { projectionUseCase.applyRevokedOrExpired(partyId, consentId, any()) }
    }

    @Test
    fun `consume ignores a ConsentRevoked for any other granteeId`(): Unit = runBlocking {
        consumer.consume(revoked(granteeId = "some-tpp-eidas-org-id"))

        coVerify(exactly = 0) { projectionUseCase.applyRevokedOrExpired(any(), any(), any()) }
    }

    @Test
    fun `consume ignores an unrelated event type for the marketing granteeId without throwing`(): Unit = runBlocking {
        consumer.consume(revoked(eventType = "ConsentRejected"))

        coVerify(exactly = 0) { projectionUseCase.applyRevokedOrExpired(any(), any(), any()) }
        coVerify(exactly = 0) { projectionUseCase.applyGranted(any(), any(), any()) }
    }

    // Poison-pill safety (matches KycAmlEventConsumer's convention): malformed JSON must be
    // swallowed, not thrown, so the consumer group is never wedged by one bad record.
    @Test
    fun `consume swallows malformed JSON without throwing`(): Unit = runBlocking {
        consumer.consume("not json at all {{{")
        // no assertion needed beyond "did not throw" — the try/catch is the behavior under test
    }

    @Test
    fun `consume swallows a payload missing partyId without throwing`(): Unit = runBlocking {
        consumer.consume(
            """{"aggregateId":"$consentId","granteeId":"party-service:marketing-comms",
                "eventType":"ConsentGranted"}""",
        )

        coVerify(exactly = 0) { projectionUseCase.applyGranted(any(), any(), any()) }
    }

    // A missing/unparseable occurredAt must still process the event (partyId/consentId/eventType
    // are all present and valid) rather than being swallowed like a truly malformed payload —
    // the fallback-to-now() path, not the poison-pill path.
    @Test
    fun `consume still applies the event when occurredAt is missing, falling back to now`(): Unit = runBlocking {
        coEvery { projectionUseCase.applyGranted(any(), any(), any()) } returns Unit

        consumer.consume(
            """{"aggregateId":"$consentId","partyId":"$partyId","granteeId":"party-service:marketing-comms",
                "granteeType":"INTERNAL_SERVICE","scopes":["MARKETING_COMMS_EMAIL"],
                "eventType":"ConsentGranted","version":1}""",
        )

        coVerify(exactly = 1) { projectionUseCase.applyGranted(partyId, consentId, any()) }
    }
}
