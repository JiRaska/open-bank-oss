// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AmlCaseServiceTest {

    private lateinit var amlCaseRepository: AmlCaseRepository

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: AmlCaseService

    @BeforeEach
    fun setUp() {
        amlCaseRepository = mockk()
        service = AmlCaseService(amlCaseRepository, objectMapper, clock)
    }

    @Test
    fun `create case sets under review for high risk and writes the created event to the outbox`(): Unit = runBlocking {
        val command = createCommand(riskLevel = AmlRiskLevel.HIGH)

        coEvery { amlCaseRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { amlCaseRepository.save(any(), any()) } answers { firstArg() }

        val result = service.createCase(command)

        assertThat(result.status).isEqualTo(AmlCaseStatus.UNDER_REVIEW)
        assertThat(result.customerReference).isEqualTo("CUST-1001")
        assertThat(result.alertCode).isEqualTo("TM-900")
        assertThat(result.matchedEntity).isEqualTo("Listed Merchant")

        // The aggregate and its domain event are handed to the repository together (ADR-0050): the
        // event is keyed on the case id (aggregateId, N2) and carries the versioned event type (N3).
        coVerify {
            amlCaseRepository.save(
                match {
                    it.idempotencyKey == command.idempotencyKey &&
                        it.riskLevel == AmlRiskLevel.HIGH &&
                        it.status == AmlCaseStatus.UNDER_REVIEW &&
                        it.alertDetail == "Multiple rapid inbound transfers"
                },
                match {
                    it.aggregateId == result.id &&
                        it.eventType == AmlCaseService.EVENT_CASE_CREATED &&
                        it.payload.contains(result.id.toString())
                },
            )
        }
    }

    @Test
    fun `create case replays existing AML case for idempotency key`(): Unit = runBlocking {
        val existing = amlCase()
        coEvery { amlCaseRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

        val result = service.createCase(createCommand(idempotencyKey = existing.idempotencyKey))

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { amlCaseRepository.save(any(), any()) }
    }

    @Test
    fun `blocking AML case requires decision reason`() {
        val existing = amlCase(status = AmlCaseStatus.UNDER_REVIEW)
        coEvery { amlCaseRepository.findById(existing.id) } returns existing

        assertThatThrownBy {
            runBlocking {
                service.updateDecision(
                    UpdateAmlDecisionCommand(
                        caseId = existing.id,
                        targetStatus = AmlCaseStatus.BLOCKED,
                        decisionReason = "   ",
                        assignedAnalyst = "analyst-1",
                        decidedBy = "reviewer-1",
                    ),
                )
            }
        }
            .isInstanceOf(InvalidAmlCaseStateTransitionException::class.java)
            .hasMessageContaining("decisionReason is required")

        coVerify(exactly = 0) { amlCaseRepository.update(any(), any()) }
    }

    /**
     * #5837 regression. `AmlCase.transitionTo` carries `now: Instant = Instant.EPOCH`, and
     * `AmlCaseService.updateDecision` never passed a value — so every terminal AML decision
     * (an analyst's four-eyes decision AND the sandbox auto-clear) recorded
     * `decidedAt`/`updatedAt` as 1970-01-01, in the DB column and on the wire. The service
     * already holds an injected `Clock`; nothing read it on this path.
     *
     * Assert RECENCY against the fixed clock, never `isNotNull()` — a non-null assertion
     * passes happily against `Instant.EPOCH`, which is why the existing suite (fixed clock
     * and all) never saw this.
     */
    @Test
    fun `decision stamps decidedAt and updatedAt from the clock, not Instant EPOCH`(): Unit = runBlocking {
        val existing = amlCase(status = AmlCaseStatus.UNDER_REVIEW)
        coEvery { amlCaseRepository.findById(existing.id) } returns existing
        coEvery { amlCaseRepository.update(any(), any()) } answers { firstArg() }

        val result = service.updateDecision(
            UpdateAmlDecisionCommand(
                caseId = existing.id,
                targetStatus = AmlCaseStatus.CLEARED,
                decisionReason = "No adverse match",
                assignedAnalyst = "analyst-1",
                decidedBy = "reviewer-1",
            ),
        )

        val expected = Instant.now(clock)
        assertThat(result.decidedAt).isEqualTo(expected)
        assertThat(result.updatedAt).isEqualTo(expected)
        assertThat(result.decidedAt).isNotEqualTo(Instant.EPOCH)

        // The status-changed event reads `current.updatedAt` for its `occurredAt`, so an EPOCH
        // aggregate silently puts 1970 on the wire for every downstream consumer too.
        val event = slot<OutboxMessage>()
        coVerify { amlCaseRepository.update(any(), capture(event)) }
        val payload = objectMapper.readTree(event.captured.payload)
        // Read it back through the same mapper that wrote it — this one serialises Instant as a
        // numeric epoch, so `Instant.parse(asText())` would fail on the shape, not the value.
        val occurredAt = objectMapper.treeToValue(payload.path("occurredAt"), Instant::class.java)
        assertThat(occurredAt).isEqualTo(expected)
    }

    private fun createCommand(idempotencyKey: String = "aml-idem-1", riskLevel: AmlRiskLevel = AmlRiskLevel.HIGH) =
        CreateAmlCaseCommand(
            idempotencyKey = idempotencyKey,
            partyId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            customerReference = "  CUST-1001  ",
            screeningType = ScreeningType.TRANSACTION_MONITORING,
            riskLevel = riskLevel,
            alertCode = "  TM-900  ",
            alertDetail = "  Multiple rapid inbound transfers  ",
            matchedEntity = "  Listed Merchant  ",
        )

    private fun amlCase(status: AmlCaseStatus = AmlCaseStatus.OPEN) = AmlCase(
        id = UUID.randomUUID(),
        idempotencyKey = "aml-idem-existing",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        transactionId = UUID.randomUUID(),
        customerReference = "CUST-1001",
        screeningType = ScreeningType.TRANSACTION_MONITORING,
        riskLevel = AmlRiskLevel.MEDIUM,
        status = status,
        alertCode = "TM-900",
        alertDetail = "Multiple rapid inbound transfers",
        matchedEntity = "Listed Merchant",
        decisionReason = null,
        assignedAnalyst = null,
        decidedBy = null,
        screenedAt = Instant.now(),
        decidedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
