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
        val built = mutableListOf<OutboxMessage>()
        coEvery { amlCaseRepository.save(any(), any()) } answers {
            built += secondArg<(AmlCase) -> OutboxMessage>()(firstArg())
            firstArg()
        }

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
                any(),
            )
        }
        assertThat(built).singleElement().satisfies({
            assertThat(it.aggregateId).isEqualTo(result.id)
            assertThat(it.eventType).isEqualTo(AmlCaseService.EVENT_CASE_CREATED)
            assertThat(it.payload).contains(result.id.toString())
        })
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
    fun `the hold intake refuses a hold whose key is held by a case of another origin`() {
        // Stored as the create endpoint stored it before the namespace was reserved: with a request
        // hash and without provenance. A REST origin must fare no better.
        for (foreignOrigin in listOf(null, CaseOrigin.REST, CaseOrigin.PACK_EVALUATION)) {
            val planted = amlCase().copy(idempotencyKey = holdKey, requestHash = "a".repeat(64), origin = foreignOrigin)
            coEvery { amlCaseRepository.findByIdempotencyKey(holdKey) } returns planted

            // An IllegalStateException: the intake's retry treats it as deterministic and dead-letters at once.
            assertThatThrownBy { runBlocking { service.createCase(holdCommand()) } }
                .describedAs("origin %s", foreignOrigin)
                .isInstanceOf(ForeignCaseUnderHoldKeyException::class.java)
                .isInstanceOf(IllegalStateException::class.java)
        }
        // Nothing adopted, nothing written.
        coVerify(exactly = 0) { amlCaseRepository.save(any(), any()) }
        assertThat(meters.counter(AmlCaseService.FOREIGN_KEY_CLAIM_METRIC).count()).isEqualTo(3.0)
    }

    @Test
    fun `a redelivered hold finds the intake's own case`(): Unit = runBlocking {
        val own = amlCase().copy(idempotencyKey = holdKey, origin = CaseOrigin.PAYMENT_HOLD_INTAKE)
        coEvery { amlCaseRepository.findByIdempotencyKey(holdKey) } returns own

        assertThat(service.createCase(holdCommand())).isEqualTo(own)

        coVerify(exactly = 0) { amlCaseRepository.save(any(), any()) }
        assertThat(meters.counter(AmlCaseService.FOREIGN_KEY_CLAIM_METRIC).count()).isZero()
    }

    @Test
    fun `only the hold intake refuses - any other opener replays the case under its key`(): Unit = runBlocking {
        val existing = amlCase().copy(idempotencyKey = "aml-idem-1", origin = null)
        coEvery { amlCaseRepository.findByIdempotencyKey("aml-idem-1") } returns existing

        for (origin in CaseOrigin.entries - CaseOrigin.PAYMENT_HOLD_INTAKE) {
            assertThat(service.createCase(createCommand().copy(origin = origin))).isEqualTo(existing)
        }
        assertThat(meters.counter(AmlCaseService.FOREIGN_KEY_CLAIM_METRIC).count()).isZero()
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
            // The party was never established: the event publishes none, not a stand-in for one.
            assertThat(payload.has("partyId")).isTrue()
            assertThat(payload.get("partyId").isNull).isTrue()
            // ADR-0374: provenance on the wire, so a rail acts only on the intake's own case.
            assertThat(payload.get("origin").asText()).isEqualTo("PAYMENT_HOLD_INTAKE")
        }

    @Test
    fun `a decision on a case the intake did not open carries no origin`(): Unit = runBlocking {
        val rest = amlCase(AmlCaseStatus.UNDER_REVIEW).copy(origin = CaseOrigin.REST)
        val event = slot<OutboxMessage>()
        coEvery { amlCaseRepository.findById(rest.id) } returns rest
        coEvery { amlCaseRepository.update(any(), capture(event)) } answers { firstArg() }

        service.updateDecision(UpdateAmlDecisionCommand(rest.id, AmlCaseStatus.CLEARED, "false positive", null, "analyst"))

        assertThat(objectMapper.readTree(event.captured.payload).has("origin")).isFalse()
    }

    @Test
    fun `a case opened without a party is stored without one and its created event says so`(): Unit = runBlocking {
        val saved = slot<AmlCase>()
        val event = mutableListOf<OutboxMessage>()
        coEvery { amlCaseRepository.findByIdempotencyKey(holdKey) } returns null
        coEvery { amlCaseRepository.save(capture(saved), any()) } answers {
            event += secondArg<(AmlCase) -> OutboxMessage>()(firstArg())
            firstArg()
        }

        service.createCase(holdCommand(partyId = null))

        assertThat(saved.captured.partyId).isNull()
        assertThat(saved.captured.partyEstablished).isFalse()
        assertThat(saved.captured.accountId).isNotNull()
        val payload = objectMapper.readTree(event.single().payload)
        assertThat(payload.has("partyId")).isTrue()
        assertThat(payload.get("partyId").isNull).isTrue()
    }

    @Test
    fun `a case with a party publishes it`(): Unit = runBlocking {
        val event = mutableListOf<OutboxMessage>()
        val command = createCommand()
        coEvery { amlCaseRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { amlCaseRepository.save(any(), any()) } answers {
            event += secondArg<(AmlCase) -> OutboxMessage>()(firstArg())
            firstArg()
        }

        val created = service.createCase(command)

        val payload = objectMapper.readTree(event.single().payload)
        assertThat(payload.get("partyId").asText()).isEqualTo(created.partyId.toString())
    }

    /**
     * The wire of a case this service could already store before it learned to store a missing party
     * is not this change's to alter: other services read it, deploy on their own schedule, and at
     * least one refuses an event without a `partyId`. A rail that cannot name the party files the
     * debtor ACCOUNT id in its place, so "party == account" is an everyday row, and it must publish
     * exactly what it always did. The expected documents are written out, field for field, from the
     * event classes as they stand on the main line; nothing here is derived from the code under test.
     */
    @Test
    fun `a case whose party is the account id publishes the same two events it always did`(): Unit = runBlocking {
        val account = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val payment = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
        val created = mutableListOf<OutboxMessage>()
        val changed = slot<OutboxMessage>()
        val command = createCommand(idempotencyKey = "aml-$payment-SANCTIONS").copy(
            partyId = account,
            accountId = account,
            transactionId = payment,
            customerReference = "1234567890/0800",
            alertCode = "SANCTIONS_HIT",
            matchedEntity = "Listed Merchant",
        )
        coEvery { amlCaseRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { amlCaseRepository.save(any(), any()) } answers {
            created += secondArg<(AmlCase) -> OutboxMessage>()(firstArg())
            firstArg()
        }
        coEvery { amlCaseRepository.update(any(), capture(changed)) } answers { firstArg() }

        val case = service.createCase(command)
        coEvery { amlCaseRepository.findById(case.id) } returns case
        service.updateDecision(UpdateAmlDecisionCommand(case.id, AmlCaseStatus.BLOCKED, "listed", null, "analyst"))

        // 2024-01-15T12:00:00Z, as this mapper writes an Instant.
        val at = "1705320000.000000000"
        assertThat(objectMapper.readTree(created.single().payload)).isEqualTo(
            objectMapper.readTree(
                """
                {"caseId":"${case.id}","idempotencyKey":"aml-$payment-SANCTIONS","partyId":"$account",
                 "accountId":"$account","transactionId":"$payment","customerReference":"1234567890/0800",
                 "screeningType":"TRANSACTION_MONITORING","riskLevel":"HIGH","status":"UNDER_REVIEW",
                 "alertCode":"SANCTIONS_HIT","matchedEntity":"Listed Merchant","occurredAt":$at}
                """,
            ),
        )
        assertThat(objectMapper.readTree(changed.captured.payload)).isEqualTo(
            objectMapper.readTree(
                """
                {"caseId":"${case.id}","partyId":"$account","previousStatus":"UNDER_REVIEW","newStatus":"BLOCKED",
                 "decisionReason":"listed","assignedAnalyst":null,"decidedBy":"analyst","occurredAt":$at,
                 "transactionId":"$payment","alertCode":"SANCTIONS_HIT","idempotencyKey":"aml-$payment-SANCTIONS"}
                """,
            ),
        )
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
