// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.application.usecase

import com.openbank.dispute.application.port.out.DisputeEvidenceRepository
import com.openbank.dispute.application.port.out.DisputeRepository
import com.openbank.dispute.application.port.out.DisputeTimelineRepository
import com.openbank.dispute.domain.model.Dispute
import com.openbank.dispute.domain.model.DisputeEvidence
import com.openbank.dispute.domain.model.DisputeResolution
import com.openbank.dispute.domain.model.DisputeStatus
import com.openbank.dispute.domain.model.DisputeTimelineEvent
import com.openbank.dispute.domain.model.DisputeType
import com.openbank.dispute.domain.model.EvidenceChain
import com.openbank.dispute.domain.model.OpenDisputeRequest
import com.openbank.dispute.domain.model.RemediationOutcome
import com.openbank.dispute.domain.model.ResolveDisputeRequest
import com.openbank.dispute.domain.model.UpdateDisputeRequest
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.audit.AuditEventTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DisputeServiceTest {

    private val clock = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)
    private val today = LocalDate.now(clock)

    private lateinit var disputeRepo: DisputeRepository
    private lateinit var evidenceRepo: DisputeEvidenceRepository
    private lateinit var timelineRepo: DisputeTimelineRepository

    private lateinit var service: DisputeService

    @BeforeEach
    fun setUp() {
        disputeRepo = mockk()
        evidenceRepo = mockk()
        timelineRepo = mockk()
        service = DisputeService(
            disputeRepo,
            evidenceRepo,
            timelineRepo,
            resolutionSlaDays = 45L,
            chargebackWindowDays = 120L,
            clock = clock,
        )
    }

    @Test
    fun `open persists dispute and adds timeline event`() {
        val request = OpenDisputeRequest(
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.UNAUTHORIZED,
            amount = BigDecimal("25.00"),
            transactionDate = today,
            description = "Unauthorized card payment",
        )

        val persistedId = UUID.randomUUID()

        val outbox = slot<List<OutboxMessage>>()
        every { disputeRepo.save(any(), capture(outbox)) } answers {
            Uni.createFrom().item(firstArg<Dispute>().copy(id = persistedId))
        }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.open(request).await().indefinitely()

        // The timeline row is an audit entry INSIDE this service; the outbox message is the only
        // thing anyone else can see. Opening a dispute used to write the first and not the second,
        // which is why ADR-0220 D1's exclusion could not be built (#4070).
        assertThat(outbox.captured.map { it.eventType }).containsExactly("dispute.opened")
        val payload = outbox.captured.single().payload
        assertThat(payload)
            .describedAs("a consumer holding only a disputeId cannot tell whose dispute it is")
            .contains(""""partyId":"${request.partyId}"""")
        // NOT asserted against persistedId: the outbox message is built from the dispute before it
        // is handed to the repository, and this mock rewrites the id on the way back. Real
        // persistence keeps it — the id is application-assigned — so the two agree in production
        // and only the stub makes them differ.
        assertThat(payload).contains(""""eventType":"dispute.opened"""")

        assertThat(result.id).isEqualTo(persistedId)
        assertThat(result.status).isEqualTo(DisputeStatus.OPEN)
        assertThat(result.resolution).isEqualTo(DisputeResolution.PENDING)
        assertThat(result.filingDate).isEqualTo(today)
        assertThat(result.resolutionDeadline).isEqualTo(today.plusDays(45))

        verify(exactly = 1) { disputeRepo.save(any(), any()) }
        verify(exactly = 1) { timelineRepo.save(any()) }
    }

    /**
     * #3994/#5256: `sourceService` is the strongest (EVENT-sourced) attribution
     * `AuditConsumer.resolveSourceService` reads. Before this field, `TopicAttribution` already
     * resolved `openbank.dispute.events` -> `dispute-service` correctly, but only as
     * TOPIC-sourced — and audit-service subscribes to this topic today, so this is a live
     * attribution upgrade, not a forward-looking one.
     */
    @Test
    fun `open publishes sourceService in the payload body for AuditConsumer attribution`() {
        val request = OpenDisputeRequest(
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.UNAUTHORIZED,
            amount = BigDecimal("25.00"),
            transactionDate = today,
            description = "Unauthorized card payment",
        )
        val outbox = slot<List<OutboxMessage>>()
        every { disputeRepo.save(any(), capture(outbox)) } answers { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        service.open(request).await().indefinitely()

        assertThat(outbox.captured.single().payload).contains(""""sourceService":"dispute-service"""")
    }

    @Test
    fun `update changes status and emits timeline event`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-1000",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.DUPLICATE,
            amount = BigDecimal("10.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        val update = UpdateDisputeRequest(status = DisputeStatus.RESOLVED_CUSTOMER, resolvedBy = "caseworker")
        val outbox = slot<List<OutboxMessage>>()
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any(), capture(outbox)) } answers { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.update(id, update).await().indefinitely()

        // The dispute ENDS here, so it must announce itself: engagement-service lifts its ADR-0220
        // targeting exclusion only on `dispute.resolved`, so without this the customer stays
        // excluded forever. The timeline row is internal and reaches no consumer.
        assertThat(outbox.captured).singleElement()
            .satisfies({ m -> assertThat(m.eventType).isEqualTo("dispute.resolved") })
        assertThat(outbox.captured.single().payload).contains(existing.partyId.toString())

        assertThat(result.status).isEqualTo(DisputeStatus.RESOLVED_CUSTOMER)
        assertThat(result.resolvedBy).isEqualTo("caseworker")
        assertThat(result.resolvedAt).isNotNull()
        assertThat(result.updatedAt).isNotNull()

        verify(exactly = 1) { disputeRepo.findById(id) }
        verify(exactly = 1) { disputeRepo.update(any(), any()) }
        verify(exactly = 1) { timelineRepo.save(any()) }
    }

    @Test
    fun `a non-terminal status change announces nothing`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-3000",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.OTHER,
            amount = BigDecimal("5.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        val outbox = slot<List<OutboxMessage>>()
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any(), capture(outbox)) } answers { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.update(id, UpdateDisputeRequest(status = DisputeStatus.UNDER_REVIEW)).await()
            .indefinitely()

        // The counter-case to the two tests above. Without it, "emits on terminal" would also pass
        // for an implementation that emits on EVERY update -- which would tell engagement to lift
        // an exclusion while the dispute is still open.
        assertThat(result.status).isEqualTo(DisputeStatus.UNDER_REVIEW)
        assertThat(result.resolvedAt).isNull()
        assertThat(outbox.captured).isEmpty()
    }

    @Test
    fun `withdraw sets withdrawn status`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-2000",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.OTHER,
            amount = BigDecimal("99.99"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        val outbox = slot<List<OutboxMessage>>()
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any(), capture(outbox)) } answers { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.withdraw(id, "customer").await().indefinitely()

        // `withdraw` delegates to `update`, so before this fix EVERY withdrawal was silent.
        assertThat(outbox.captured).singleElement()
            .satisfies({ m -> assertThat(m.eventType).isEqualTo("dispute.resolved") })
        // WITHDRAWN carries no remediation outcome: it must serialise as JSON null, not as the
        // four-character string "null" inside quotes.
        assertThat(outbox.captured.single().payload).contains("\"outcome\":null")

        assertThat(result.status).isEqualTo(DisputeStatus.WITHDRAWN)
        assertThat(result.resolution).isEqualTo(DisputeResolution.WITHDRAWN)
        assertThat(result.resolvedBy).isEqualTo("customer")
        assertThat(result.updatedAt).isNotNull()

        verify(exactly = 1) { disputeRepo.findById(id) }
        verify(exactly = 1) { disputeRepo.update(any(), any()) }
        verify(exactly = 1) { timelineRepo.save(any()) }
    }

    @Test
    fun `addEvidence chains the first item from genesis`() {
        val disputeId = UUID.randomUUID()
        val submitted = DisputeEvidence(disputeId = disputeId, submittedBy = "customer", evidenceType = "STATEMENT")

        every { evidenceRepo.findLatestByDisputeId(disputeId) } returns Uni.createFrom().item(null as DisputeEvidence?)
        every { evidenceRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeEvidence>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.addEvidence(disputeId, submitted).await().indefinitely()

        assertThat(result.sequence).isEqualTo(0)
        assertThat(result.prevHash).isEqualTo(EvidenceChain.GENESIS_HASH)
        assertThat(result.recordHash).isNotNull()
    }

    @Test
    fun `addEvidence chains a second item from the stored tail`() {
        val disputeId = UUID.randomUUID()
        val previous = EvidenceChain.append(
            DisputeEvidence(
                disputeId = disputeId,
                submittedBy = "customer",
                evidenceType = "STATEMENT",
                submittedAt = now,
            ),
            previous = null,
        )
        val submitted = DisputeEvidence(disputeId = disputeId, submittedBy = "ops", evidenceType = "TRANSACTION_REF")

        every { evidenceRepo.findLatestByDisputeId(disputeId) } returns Uni.createFrom().item(previous)
        every { evidenceRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeEvidence>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.addEvidence(disputeId, submitted).await().indefinitely()

        assertThat(result.sequence).isEqualTo(1)
        assertThat(result.prevHash).isEqualTo(previous.recordHash)
    }

    @Test
    fun `resolve with UPHELD transitions to RESOLVED_CUSTOMER and emits both outbox events`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-3000",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.UNAUTHORIZED,
            status = DisputeStatus.UNDER_REVIEW,
            amount = BigDecimal("50.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        val request = ResolveDisputeRequest(outcome = RemediationOutcome.UPHELD, resolvedBy = "caseworker")
        val messagesSlot = slot<List<OutboxMessage>>()

        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any(), capture(messagesSlot)) } answers
            { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.resolve(id, request).await().indefinitely()

        assertThat(result.status).isEqualTo(DisputeStatus.RESOLVED_CUSTOMER)
        assertThat(result.remediationOutcome).isEqualTo(RemediationOutcome.UPHELD)
        assertThat(result.remediationAmount).isEqualByComparingTo(BigDecimal("50.00"))
        assertThat(result.resolvedAt).isNotNull()

        val eventTypes = messagesSlot.captured.map { it.eventType }
        assertThat(eventTypes).containsExactlyInAnyOrder("dispute.resolved", "dispute.remediation_requested")
        val remediationEvent = messagesSlot.captured.first { it.eventType == "dispute.remediation_requested" }
        assertThat(remediationEvent.payload).contains(existing.accountId.toString())
        assertThat(remediationEvent.payload).contains("\"amount\":50.00")

        // #3994/#5256: both outbox events carry the producer's own sourceService claim.
        messagesSlot.captured.forEach {
            assertThat(it.payload).contains(""""sourceService":"dispute-service"""")
        }
    }

    /**
     * #3914: red before both payloads gained `occurredAt` — neither carried an event time
     * `AuditConsumer` reads, so both audit rows recorded the consumer's ingest clock. Asserts BOTH
     * messages, and asserts the value is the RESOLUTION instant rather than merely present: the
     * remediation event deliberately shares the resolved event's instant, and a fresh clock read
     * on either would put two different "when"s on one indivisible state change.
     */
    @Test
    fun `both resolve outbox payloads carry the resolution instant as the audit event time`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-3001",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.UNAUTHORIZED,
            status = DisputeStatus.UNDER_REVIEW,
            amount = BigDecimal("50.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        val messagesSlot = slot<List<OutboxMessage>>()
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any(), capture(messagesSlot)) } answers
            { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val resolved = service.resolve(
            id,
            ResolveDisputeRequest(outcome = RemediationOutcome.UPHELD, resolvedBy = "caseworker"),
        ).await().indefinitely()

        val resolvedAt = requireNonNull(resolved.resolvedAt).toInstant()
        assertThat(messagesSlot.captured).hasSize(2)
        messagesSlot.captured.forEach { AuditEventTime.assertRecordedAsEventTime(it.payload, resolvedAt) }
    }

    /**
     * #8352: red against `origin/main`, where `dispute.opened` carried `openedAt` and no
     * `occurredAt` — so every audit row for the moment a customer disputed a payment recorded the
     * audit consumer's ingest clock instead.
     *
     * Both halves are asserted, and the second is the one a rename alone would not have satisfied:
     * `Dispute.createdAt` is an `OffsetDateTime`, so interpolating it directly renders an offset
     * form rather than the `Z`-normalised instant the two sibling builders in the same file emit.
     * `assertRecordedAsEventTime` compares the parsed value, so a payload carrying the right key
     * with the wrong form fails here rather than downstream.
     *
     * The sibling assertion on `openedAt` is not decoration: this change had to be additive, and a
     * test that only looked at the new key could not tell an addition from a rename.
     */
    @Test
    fun `the opened outbox payload carries the opening instant as the audit event time`() {
        val request = OpenDisputeRequest(
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.UNAUTHORIZED,
            amount = BigDecimal("25.00"),
            transactionDate = today,
            description = "Unauthorized card payment",
        )
        val outbox = slot<List<OutboxMessage>>()
        every { disputeRepo.save(any(), capture(outbox)) } answers { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        service.open(request).await().indefinitely()

        val payload = outbox.captured.single().payload
        AuditEventTime.assertRecordedAsEventTime(payload, now.toInstant())
        assertThat(payload)
            .describedAs("occurredAt is ADDITIVE — openedAt keeps its name, place and form")
            .contains(""""openedAt":"$now"""")
    }

    private fun <T> requireNonNull(value: T?): T = requireNotNull(value) { "resolvedAt must be set by resolve()" }

    @Test
    fun `resolve with REJECTED emits only the resolved event, no remediation amount`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-3001",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.OTHER,
            status = DisputeStatus.OPEN,
            amount = BigDecimal("75.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        val request = ResolveDisputeRequest(outcome = RemediationOutcome.REJECTED, resolvedBy = "caseworker")
        val messagesSlot = slot<List<OutboxMessage>>()

        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any(), capture(messagesSlot)) } answers
            { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.resolve(id, request).await().indefinitely()

        assertThat(result.status).isEqualTo(DisputeStatus.RESOLVED_MERCHANT)
        assertThat(result.remediationAmount).isNull()
        assertThat(messagesSlot.captured.map { it.eventType }).containsExactly("dispute.resolved")
    }

    @Test
    fun `resolve with PARTIAL requires a valid remediationAmount`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-3002",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.OTHER,
            status = DisputeStatus.OPEN,
            amount = BigDecimal("100.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)

        val tooHigh = ResolveDisputeRequest(
            outcome = RemediationOutcome.PARTIAL,
            remediationAmount = BigDecimal("100.00"),
            resolvedBy = "caseworker",
        )
        val missing = ResolveDisputeRequest(outcome = RemediationOutcome.PARTIAL, resolvedBy = "caseworker")

        assertThat(runCatching { service.resolve(id, tooHigh).await().indefinitely() }.isFailure).isTrue()
        assertThat(runCatching { service.resolve(id, missing).await().indefinitely() }.isFailure).isTrue()
    }

    @Test
    fun `resolve rejects a dispute that is already terminal`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-3003",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.OTHER,
            status = DisputeStatus.WITHDRAWN,
            amount = BigDecimal("10.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)

        val request = ResolveDisputeRequest(outcome = RemediationOutcome.UPHELD, resolvedBy = "caseworker")

        assertThat(runCatching { service.resolve(id, request).await().indefinitely() }.isFailure).isTrue()
    }
}
