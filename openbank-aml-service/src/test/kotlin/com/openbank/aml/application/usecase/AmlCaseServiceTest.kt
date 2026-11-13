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
import com.openbank.aml.application.port.out.DisplacedCase
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.CaseOrigin
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    private val meters = SimpleMeterRegistry()
    private lateinit var service: AmlCaseService

    @BeforeEach
    fun setUp() {
        amlCaseRepository = mockk()
        service = AmlCaseService(amlCaseRepository, objectMapper, clock, meters)
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
        // #4403: a rail acting on one kind of case reads these two to find its payment.
        assertThat(payload.get("alertCode").asText()).isEqualTo("TM-900")
        assertThat(payload.hasNonNull("transactionId")).isTrue()
        // Read it back through the same mapper that wrote it — this one serialises Instant as a
        // numeric epoch, so `Instant.parse(asText())` would fail on the shape, not the value.
        val occurredAt = objectMapper.treeToValue(payload.path("occurredAt"), Instant::class.java)
        assertThat(occurredAt).isEqualTo(expected)
    }

    private val holdKey = "${AmlCase.PAYMENT_HOLD_KEY_PREFIX}domestic:${UUID.randomUUID()}"

    private fun holdCommand(partyId: UUID? = UUID.randomUUID()) =
        createCommand(idempotencyKey = holdKey).copy(origin = CaseOrigin.PAYMENT_HOLD_INTAKE, partyId = partyId)

    @Test
    fun `the hold intake does not take a case of another origin under its key for its own`(): Unit = runBlocking {
        // Stored as the create endpoint stored it before the namespace was reserved: with a request
        // hash and without provenance. A REST origin must fare no better.
        for (foreignOrigin in listOf(null, CaseOrigin.REST, CaseOrigin.PACK_EVALUATION)) {
            val planted = amlCase().copy(idempotencyKey = holdKey, requestHash = "a".repeat(64), origin = foreignOrigin)
            val opened = slot<AmlCase>()
            val event = slot<OutboxMessage>()
            coEvery { amlCaseRepository.findByIdempotencyKey(holdKey) } returns planted
            coEvery {
                amlCaseRepository.save(
                    capture(opened),
                    capture(event),
                    DisplacedCase(planted.id, "quarantined:${planted.id}"),
                )
            } answers { firstArg() }

            val result = service.createCase(holdCommand())

            assertThat(result.id).describedAs("origin %s", foreignOrigin).isNotEqualTo(planted.id)
            assertThat(result).isEqualTo(opened.captured)
            assertThat(opened.captured.idempotencyKey).isEqualTo(holdKey)
            assertThat(opened.captured.origin).isEqualTo(CaseOrigin.PAYMENT_HOLD_INTAKE)
            assertThat(event.captured.eventType).isEqualTo(AmlCaseService.EVENT_CASE_CREATED)
            assertThat(event.captured.aggregateId).isEqualTo(result.id)
        }
        coVerify(exactly = 0) { amlCaseRepository.save(any(), any(), null) }
        assertThat(meters.counter(AmlCaseService.FOREIGN_KEY_CLAIM_METRIC).count()).isEqualTo(3.0)
    }

    @Test
    fun `a redelivered hold finds the intake's own case and displaces nothing`(): Unit = runBlocking {
        val own = amlCase().copy(idempotencyKey = holdKey, origin = CaseOrigin.PAYMENT_HOLD_INTAKE)
        coEvery { amlCaseRepository.findByIdempotencyKey(holdKey) } returns own

        assertThat(service.createCase(holdCommand())).isEqualTo(own)

        coVerify(exactly = 0) { amlCaseRepository.save(any(), any(), any()) }
        assertThat(meters.counter(AmlCaseService.FOREIGN_KEY_CLAIM_METRIC).count()).isZero()
    }

    @Test
    fun `only the hold intake displaces - any other opener replays the case under its key`(): Unit = runBlocking {
        val existing = amlCase().copy(idempotencyKey = "aml-idem-1", origin = null)
        coEvery { amlCaseRepository.findByIdempotencyKey("aml-idem-1") } returns existing

        for (origin in CaseOrigin.entries - CaseOrigin.PAYMENT_HOLD_INTAKE) {
            assertThat(service.createCase(createCommand().copy(origin = origin))).isEqualTo(existing)
        }
        coVerify(exactly = 0) { amlCaseRepository.save(any(), any(), any()) }
    }

    @Test
    fun `a case under a hold key that the intake did not open cannot be decided`(): Unit = runBlocking {
        for (foreignOrigin in listOf(null, CaseOrigin.REST)) {
            val planted = amlCase().copy(idempotencyKey = holdKey, origin = foreignOrigin)
            coEvery { amlCaseRepository.findById(planted.id) } returns planted
            for (target in listOf(AmlCaseStatus.CLEARED, AmlCaseStatus.BLOCKED, AmlCaseStatus.UNDER_REVIEW)) {
                assertThatThrownBy {
                    runBlocking {
                        service.updateDecision(UpdateAmlDecisionCommand(planted.id, target, "reason", null, "analyst"))
                    }
                }.describedAs("origin %s -> %s", foreignOrigin, target)
                    .isInstanceOf(InvalidAmlCaseStateTransitionException::class.java)
                    .hasMessageContaining("held-payment key")
            }
        }
        coVerify(exactly = 0) { amlCaseRepository.update(any(), any()) }
    }

    @Test
    fun `the intake's own case is decided by one analyst and its event carries the key the rail matches`(): Unit =
        runBlocking {
            val own = amlCase(AmlCaseStatus.UNDER_REVIEW)
                .copy(idempotencyKey = holdKey, origin = CaseOrigin.PAYMENT_HOLD_INTAKE, partyId = null)
            val event = slot<OutboxMessage>()
            coEvery { amlCaseRepository.findById(own.id) } returns own
            coEvery { amlCaseRepository.update(any(), capture(event)) } answers { firstArg() }

            val decided = service.updateDecision(
                UpdateAmlDecisionCommand(own.id, AmlCaseStatus.CLEARED, "false positive", null, "analyst"),
            )

            assertThat(decided.status).isEqualTo(AmlCaseStatus.CLEARED)
            val payload = objectMapper.readTree(event.captured.payload)
            assertThat(payload.get("idempotencyKey").asText()).isEqualTo(holdKey)
            // The party was never established: the wire says so, and fills the required slot with the nil UUID.
            assertThat(payload.get("partyResolved").asBoolean()).isFalse()
            assertThat(payload.get("partyId").asText()).isEqualTo("00000000-0000-0000-0000-000000000000")
        }

    @Test
    fun `a case opened without a party is stored without one and its created event says so`(): Unit = runBlocking {
        val saved = slot<AmlCase>()
        val event = slot<OutboxMessage>()
        coEvery { amlCaseRepository.findByIdempotencyKey(holdKey) } returns null
        coEvery { amlCaseRepository.save(capture(saved), capture(event)) } answers { firstArg() }

        service.createCase(holdCommand(partyId = null))

        assertThat(saved.captured.partyId).isNull()
        assertThat(saved.captured.partyResolved).isFalse()
        assertThat(saved.captured.accountId).isNotNull()
        val payload = objectMapper.readTree(event.captured.payload)
        assertThat(payload.get("partyResolved").asBoolean()).isFalse()
        assertThat(payload.get("partyId").asText()).isEqualTo("00000000-0000-0000-0000-000000000000")
    }

    @Test
    fun `a case with a party says it is resolved on both events`(): Unit = runBlocking {
        val event = slot<OutboxMessage>()
        val command = createCommand()
        coEvery { amlCaseRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { amlCaseRepository.save(any(), capture(event)) } answers { firstArg() }

        val created = service.createCase(command)

        val payload = objectMapper.readTree(event.captured.payload)
        assertThat(payload.get("partyResolved").asBoolean()).isTrue()
        assertThat(payload.get("partyId").asText()).isEqualTo(created.partyId.toString())
    }

    private fun createCommand(idempotencyKey: String = "aml-idem-1", riskLevel: AmlRiskLevel = AmlRiskLevel.HIGH) =
        CreateAmlCaseCommand(
            idempotencyKey = idempotencyKey,
            origin = CaseOrigin.REST,
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
