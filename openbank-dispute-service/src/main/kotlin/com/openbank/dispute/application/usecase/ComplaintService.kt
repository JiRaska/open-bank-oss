// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.application.usecase

import com.openbank.dispute.application.port.`in`.FileComplaintUseCase
import com.openbank.dispute.application.port.`in`.GetComplaintUseCase
import com.openbank.dispute.application.port.`in`.HandleComplaintUseCase
import com.openbank.dispute.application.port.out.ComplaintRepository
import com.openbank.dispute.domain.model.CloseComplaintRequest
import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintStatus
import com.openbank.dispute.domain.model.FileComplaintRequest
import com.openbank.dispute.domain.model.InterimReplyRequest
import com.openbank.dispute.domain.model.ResolveComplaintRequest
import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Complaints-handling use case (ADR-0085 §1, §2). The statutory deadline clock is **domain logic**:
 * an off-by-one in business-day math is a regulatory breach, so the math lives here against the
 * shared `BusinessCalendar` CZK (CERTIS) primitive and an injected [Clock] so "today"/breach is
 * deterministically testable — exactly like YearCloseService in openbank-ledger-service.
 *
 * Deadline rules (PSD2 Art. 101 transposition):
 *  - intake: dueDate = CZK calendar + 15 business days from receivedDate.
 *  - interim reply: dueDate extended to 35 business days from receivedDate (records a reason).
 *  - breached (derived): today > dueDate AND status not in {RESOLVED, CLOSED}.
 *
 * Metrics (ADR-0085 §2) are published by [com.openbank.dispute.infrastructure.observability.ComplaintDeadlineGauge]:
 * `openbank_complaints_open`, `openbank_complaints_due_soon`, `openbank_complaints_due_breach` — all
 * service-local gauges backed by a 30 s refresh tick (no libs fleet rebuild required).
 */
@ApplicationScoped
class ComplaintService(private val complaintRepo: ComplaintRepository, private val clock: Clock) :
    FileComplaintUseCase,
    HandleComplaintUseCase,
    GetComplaintUseCase {

    // CDI constructor (Clock is not a CDI bean): bank time is Europe/Prague, like YearCloseService.
    // Without @Inject, ArC sees two constructors, registers no bean, and the use-case interfaces are
    // unsatisfied at build time (caught only by quarkusBuild/@QuarkusTest, not by the unit gate).
    @Inject
    constructor(complaintRepo: ComplaintRepository) : this(complaintRepo, Clock.system(BANK_TIME))

    private val calendar: BusinessCalendar = BusinessCalendar.forCurrency("CZK")

    override fun file(request: FileComplaintRequest): Uni<Complaint> {
        val receivedDate = LocalDate.now(clock)
        val now = OffsetDateTime.now(clock)
        val complaint = Complaint(
            reference = "CMP-${System.currentTimeMillis()}",
            category = request.category,
            channel = request.channel,
            description = request.description,
            accountId = request.accountId,
            transactionId = request.transactionId,
            disputeId = request.disputeId,
            receivedDate = receivedDate,
            dueDate = calendar.addBusinessDays(receivedDate, STANDARD_DEADLINE_DAYS),
            createdAt = now,
            updatedAt = now,
        )
        return complaintRepo.save(complaint, outboxFor(complaint, "complaint.received"))
            .map(::withBreach)
    }

    override fun interimReply(id: UUID, request: InterimReplyRequest): Uni<Complaint> = mutate(id) { existing ->
        existing.copy(
            dueDate = calendar.addBusinessDays(existing.receivedDate, EXTENDED_DEADLINE_DAYS),
            interimReplyAt = OffsetDateTime.now(clock),
            interimReplyReason = request.reason,
            updatedAt = OffsetDateTime.now(clock),
        ) to "complaint.interim_reply"
    }

    override fun resolve(id: UUID, request: ResolveComplaintRequest): Uni<Complaint> = mutate(id) { existing ->
        existing.copy(
            status = ComplaintStatus.RESOLVED,
            outcome = request.outcome,
            redressGranted = request.redressGranted ?: existing.redressGranted,
            resolvedAt = OffsetDateTime.now(clock),
            updatedAt = OffsetDateTime.now(clock),
        ) to "complaint.resolved"
    }

    override fun close(id: UUID, request: CloseComplaintRequest): Uni<Complaint> = mutate(id) { existing ->
        existing.copy(
            status = ComplaintStatus.CLOSED,
            outcome = request.outcome,
            rootCauseCode = request.rootCauseCode,
            redressGranted = request.redressGranted ?: existing.redressGranted,
            closedAt = OffsetDateTime.now(clock),
            resolvedAt = existing.resolvedAt ?: OffsetDateTime.now(clock),
            updatedAt = OffsetDateTime.now(clock),
        ) to "complaint.closed"
    }

    override fun getComplaint(id: UUID): Uni<Complaint?> = complaintRepo.findById(id).map { it?.let(::withBreach) }

    override fun listByStatus(status: ComplaintStatus): Uni<List<Complaint>> =
        complaintRepo.findByStatus(status).map { it.map(::withBreach) }

    override fun listAll(): Uni<List<Complaint>> = complaintRepo.findAll().map { it.map(::withBreach) }

    /** Load-mutate-save with a fresh outbox event, then re-derive the breach flag. */
    private fun mutate(id: UUID, transform: (Complaint) -> Pair<Complaint, String>): Uni<Complaint> =
        complaintRepo.findById(id).flatMap { existing ->
            if (existing == null) {
                Uni.createFrom().failure(IllegalArgumentException("Complaint not found: $id"))
            } else {
                val (updated, eventType) = transform(existing)
                complaintRepo.update(updated, outboxFor(updated, eventType)).map(::withBreach)
            }
        }

    /** Derive [Complaint.breached] against the injected clock: today > dueDate and still open. */
    private fun withBreach(complaint: Complaint): Complaint =
        complaint.copy(breached = isBreached(complaint, LocalDate.now(clock)))

    companion object {
        const val STANDARD_DEADLINE_DAYS = 15
        const val EXTENDED_DEADLINE_DAYS = 35
        private val BANK_TIME: ZoneId = ZoneId.of("Europe/Prague")
    }
}

/** A complaint is breached when today is strictly past its due date and it is still open. */
internal fun isBreached(complaint: Complaint, today: LocalDate): Boolean = today.isAfter(complaint.dueDate) &&
    complaint.status != ComplaintStatus.RESOLVED &&
    complaint.status != ComplaintStatus.CLOSED

/** Build a transactional-outbox message for a complaint event (payload is a compact JSON object). */
internal fun outboxFor(complaint: Complaint, eventType: String): OutboxMessage = OutboxMessage(
    aggregateId = complaint.id,
    eventType = eventType,
    payload = complaintPayload(complaint, eventType),
)

/**
 * `occurredAt` is [Complaint.updatedAt] (#3914).
 *
 * One builder serves every complaint event, so the instant has to be one the aggregate carries for
 * ALL of them, and `updatedAt` is exactly that: each transition in [ComplaintService] sets it from
 * the injected clock as part of the same `copy` that produces the state this event describes. On
 * `complaint.received` it equals `createdAt`, which is the filing instant. `receivedDate`/`dueDate`
 * are LocalDates — regulatory deadlines, not instants — and cannot serve.
 */
private fun complaintPayload(complaint: Complaint, eventType: String): String =
    """{"eventType":"$eventType","complaintId":"${complaint.id}",""" +
        """"reference":"${complaint.reference}","category":"${complaint.category}",""" +
        """"channel":"${complaint.channel}","status":"${complaint.status}",""" +
        """"receivedDate":"${complaint.receivedDate}","dueDate":"${complaint.dueDate}",""" +
        """"occurredAt":"${complaint.updatedAt.toInstant()}"}"""
