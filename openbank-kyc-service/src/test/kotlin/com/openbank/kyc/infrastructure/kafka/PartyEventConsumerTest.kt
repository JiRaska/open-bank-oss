// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.KycCaseResult
import com.openbank.kyc.application.KycService
import com.openbank.kyc.application.PepScreeningService
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.RiskLevel
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PartyEventConsumerTest {

    private val kycService = mockk<KycService>()
    private val kycCaseRepository = mockk<KycCaseRepository>()
    private val pepScreeningService = mockk<PepScreeningService>()
    private lateinit var consumer: PartyEventConsumer

    private val fixedClock = java.time.Clock.fixed(Instant.parse("2026-01-15T03:00:00Z"), java.time.ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        consumer = PartyEventConsumer().also {
            it.kycService = kycService
            it.kycCaseRepository = kycCaseRepository
            it.pepScreeningService = pepScreeningService
            it.objectMapper = ObjectMapper()
            it.clock = fixedClock
        }
    }

    @Test
    fun `PARTY_CREATED auto-opens a KYC case and PEP-screens the party's legal name`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val case = caseFor(partyId)
        coEvery { kycService.openCaseForParty(partyId) } returns KycCaseResult(case, created = true)
        coEvery { pepScreeningService.screenCase(case.id, "Jane Doe") } returns case

        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId","legalName":"Jane Doe"}""")

        coVerify(exactly = 1) { kycService.openCaseForParty(partyId) }
        coVerify(exactly = 1) { pepScreeningService.screenCase(case.id, "Jane Doe") }
    }

    @Test
    fun `PARTY_CREATED without a legalName opens the case but skips PEP screening`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val case = caseFor(partyId)
        coEvery { kycService.openCaseForParty(partyId) } returns KycCaseResult(case, created = true)

        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { kycService.openCaseForParty(partyId) }
        coVerify(exactly = 0) { pepScreeningService.screenCase(any(), any()) }
    }

    @Test
    fun `PARTY_CREATED skips PEP screening when the sandbox auto-approve path already settled the case`(): Unit =
        runBlocking {
            // openbank.kyc.auto-approve=true (sandbox STP, ADR-0073) settles the case to APPROVED
            // before this consumer ever sees it — re-screening a terminal case here would race the
            // already-closed state rather than extend it.
            val partyId = UUID.randomUUID()
            val approvedCase = caseFor(partyId).copy(status = KycCaseStatus.APPROVED)
            coEvery { kycService.openCaseForParty(partyId) } returns KycCaseResult(approvedCase, created = true)

            consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId","legalName":"Jane Doe"}""")

            coVerify(exactly = 0) { pepScreeningService.screenCase(any(), any()) }
        }

    @Test
    fun `PARTY_CREATED on an already-open party is an idempotent no-op (reuse path)`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val case = caseFor(partyId)
        coEvery { kycService.openCaseForParty(partyId) } returns KycCaseResult(case, created = false)
        coEvery { pepScreeningService.screenCase(any(), any()) } returns case

        // Must not throw — the redelivered/replayed event reuses the existing case.
        consumer.consume("""{"eventType":"PARTY_CREATED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { kycService.openCaseForParty(partyId) }
    }

    @Test
    fun `PARTY_ERASED anonymises KYC case PII for the party`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val now = fixedClock.instant()
        coJustRun { kycCaseRepository.anonymizeByPartyId(partyId, now) }

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { kycCaseRepository.anonymizeByPartyId(partyId, now) }
        coVerify(exactly = 0) { kycService.openCaseForParty(any()) }
    }

    @Test
    fun `PARTY_ERASED anonymisation failure is swallowed to protect the consumer group`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val now = fixedClock.instant()
        coEvery { kycCaseRepository.anonymizeByPartyId(partyId, now) } throws RuntimeException("db down")

        consumer.consume("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        coVerify(exactly = 1) { kycCaseRepository.anonymizeByPartyId(partyId, now) }
    }

    @Test
    fun `unknown party events are ignored`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()

        consumer.consume("""{"eventType":"PARTY_STATUS_CHANGED","partyId":"$partyId","newStatus":"VERIFIED"}""")

        coVerify(exactly = 0) { kycService.openCaseForParty(any()) }
        coVerify(exactly = 0) { kycCaseRepository.anonymizeByPartyId(any(), any()) }
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
