// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.KycCaseResult
import com.openbank.kyc.application.KycService
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.RiskLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PartyEventConsumerTest {

    private val kycService = mockk<KycService>()
    private lateinit var consumer: PartyEventConsumer

    @BeforeEach
    fun setUp() {
        consumer = PartyEventConsumer().also {
            it.kycService = kycService
            it.objectMapper = ObjectMapper()
        }
    }

    @Test
    fun `PARTY_CREATED auto-opens a KYC case for the party`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { kycService.openCaseForParty(partyId) } returns KycCaseResult(caseFor(partyId), created = true)

        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId","legalName":"Jane Doe"}""")

        coVerify(exactly = 1) { kycService.openCaseForParty(partyId) }
    }

    @Test
    fun `PARTY_CREATED on an already-open party is an idempotent no-op (reuse path)`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { kycService.openCaseForParty(partyId) } returns KycCaseResult(caseFor(partyId), created = false)

        // Must not throw — the redelivered/replayed event reuses the existing case.
        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { kycService.openCaseForParty(partyId) }
    }

    @Test
    fun `non-create party events are ignored`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()

        consumer.consume("""{"eventType":"PARTY_STATUS_CHANGED","partyId":"$partyId","newStatus":"VERIFIED"}""")

        coVerify(exactly = 0) { kycService.openCaseForParty(any()) }
    }

    @Test
    fun `malformed payload is acked without opening a case or throwing`(): Unit = runBlocking {
        consumer.consume("not json")
        consumer.consume("""{"eventType":"PARTY_CREATED"}""") // no partyId

        coVerify(exactly = 0) { kycService.openCaseForParty(any()) }
    }

    @Test
    fun `a domain failure is swallowed so the consumer group is not wedged`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { kycService.openCaseForParty(partyId) } throws RuntimeException("db down")

        // Must not throw — the message is acked and the party stream can replay.
        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { kycService.openCaseForParty(partyId) }
    }

    private fun caseFor(partyId: UUID) = KycCase(
        id = UUID.randomUUID(),
        partyId = partyId,
        status = KycCaseStatus.OPEN,
        riskLevel = RiskLevel.MEDIUM,
        assignedTo = null,
        checks = emptyList(),
        notes = null,
        reviewedBy = null,
        reviewedAt = null,
        expiresAt = Instant.now().plusSeconds(3600),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
