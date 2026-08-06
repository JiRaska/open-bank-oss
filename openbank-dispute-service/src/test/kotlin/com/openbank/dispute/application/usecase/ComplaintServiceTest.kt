// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.application.usecase

import com.openbank.dispute.application.port.out.ComplaintRepository
import com.openbank.dispute.domain.model.CloseComplaintRequest
import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintCategory
import com.openbank.dispute.domain.model.ComplaintChannel
import com.openbank.dispute.domain.model.ComplaintStatus
import com.openbank.dispute.domain.model.FileComplaintRequest
import com.openbank.dispute.domain.model.InterimReplyRequest
import com.openbank.dispute.domain.model.ResolveComplaintRequest
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.audit.AuditEventTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

class ComplaintServiceTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val repo: ComplaintRepository = mockk()

    private fun serviceAt(today: LocalDate): ComplaintService {
        // Fix the clock at 10:00 local on [today] so LocalDate.now(clock) == today.
        val clock = Clock.fixed(today.atTime(10, 0).atZone(zone).toInstant(), zone)
        return ComplaintService(repo, clock)
    }

    private fun stubSave() {
        every { repo.save(any(), any()) } answers { Uni.createFrom().item(firstArg<Complaint>()) }
    }

    private fun fileRequest() = FileComplaintRequest(
        category = ComplaintCategory.PAYMENT_SERVICE,
        channel = ComplaintChannel.APP,
        description = "Card payment debited twice",
    )

    /**
     * #3914: red before `complaintPayload` gained `occurredAt` — the filing instant was nowhere in
     * the payload (`receivedDate`/`dueDate` are LocalDates), so every complaint audit row recorded
     * the audit consumer's ingest clock as the filing time.
     */
    @Test
    fun `the complaint payload carries the transition instant as the audit event time`() {
        val today = LocalDate.of(2026, 6, 9)
        val msg = slot<OutboxMessage>()
        every { repo.save(any(), capture(msg)) } answers { Uni.createFrom().item(firstArg<Complaint>()) }

        val filed = serviceAt(today).file(fileRequest()).await().indefinitely()

        AuditEventTime.assertRecordedAsEventTime(msg.captured.payload, filed.updatedAt.toInstant())
    }

    // ---- intake deadline clock (15 business days, CZK / CERTIS) ----

    @Test
    fun `intake computes a 15 business-day due date - plain Tuesday`() {
        // Tue 2026-06-09 + 15 business days, no holidays in window -> Tue 2026-06-30.
        stubSave()
        val service = serviceAt(LocalDate.of(2026, 6, 9))

        val result = service.file(fileRequest()).await().indefinitely()

        assertThat(result.receivedDate).isEqualTo(LocalDate.of(2026, 6, 9))
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 6, 30))
        assertThat(result.status).isEqualTo(ComplaintStatus.RECEIVED)
        assertThat(result.breached).isFalse()
        verify(exactly = 1) { repo.save(any(), any()) }
    }

    @Test
    fun `intake on a Friday skips the weekend`() {
        // Fri 2026-06-12 + 15 business days -> Fri 2026-07-03.
        stubSave()
        val result = serviceAt(LocalDate.of(2026, 6, 12)).file(fileRequest()).await().indefinitely()
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 7, 3))
    }

    @Test
    fun `intake on a Saturday counts from the weekend start`() {
        // Sat 2026-06-13 + 15 business days (first step lands Mon 06-15) -> Fri 2026-07-03.
        stubSave()
        val result = serviceAt(LocalDate.of(2026, 6, 13)).file(fileRequest()).await().indefinitely()
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 7, 3))
    }

    @Test
    fun `intake window spanning the Czech Easter holidays is extended by those holidays`() {
        // Thu 2026-04-02 + 15 business days. The window contains Good Friday (2026-04-03) and
        // Easter Monday (2026-04-06), both CERTIS holidays. Weekend-only math would give
        // 2026-04-23; the two holidays push it to Mon 2026-04-27.
        stubSave()
        val result = serviceAt(LocalDate.of(2026, 4, 2)).file(fileRequest()).await().indefinitely()
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 4, 27))
    }

    @Test
    fun `intake near month-end rolls past the month boundary`() {
        // Mon 2026-03-30 + 15 business days -> Wed 2026-04-22 (crosses March/April).
        stubSave()
        val result = serviceAt(LocalDate.of(2026, 3, 30)).file(fileRequest()).await().indefinitely()
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 4, 22))
    }

    @Test
    fun `intake window spanning the December holidays crosses into the next year`() {
        // Thu 2026-12-10 + 15 business days. Window contains 24/25/26 December (CERTIS holidays);
        // due date lands Tue 2027-01-05.
        stubSave()
        val result = serviceAt(LocalDate.of(2026, 12, 10)).file(fileRequest()).await().indefinitely()
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2027, 1, 5))
    }

    // ---- interim reply extends to 35 business days and records the reason ----

    @Test
    fun `interim reply extends the deadline to 35 business days and records the reason`() {
        // received Thu 2026-04-02: +35 business days (across Easter holidays) -> Wed 2026-05-27.
        val received = LocalDate.of(2026, 4, 2)
        val existing = baseComplaint(received = received, due = LocalDate.of(2026, 4, 27))
        every { repo.findById(existing.id) } returns Uni.createFrom().item(existing)
        val saved = slot<Complaint>()
        every { repo.update(capture(saved), any()) } answers { Uni.createFrom().item(firstArg<Complaint>()) }

        val service = serviceAt(received)
        val result = service.interimReply(existing.id, InterimReplyRequest("awaiting card-scheme response"))
            .await().indefinitely()

        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 5, 27))
        assertThat(result.interimReplyReason).isEqualTo("awaiting card-scheme response")
        assertThat(result.interimReplyAt).isNotNull()
        assertThat(result.status).isEqualTo(ComplaintStatus.RECEIVED)
        assertThat(saved.captured.dueDate).isEqualTo(LocalDate.of(2026, 5, 27))
    }

    // ---- breach flag flips correctly with a fixed clock around the due date ----

    @Test
    fun `breach is false on the due date and true the day after`() {
        val due = LocalDate.of(2026, 7, 3)
        val complaint = baseComplaint(received = LocalDate.of(2026, 6, 12), due = due)

        every { repo.findById(complaint.id) } returns Uni.createFrom().item(complaint)
        // On the due date: not breached.
        assertThat(serviceAt(due).getComplaint(complaint.id).await().indefinitely()!!.breached).isFalse()
        // One day past the due date: breached (still RECEIVED).
        assertThat(serviceAt(due.plusDays(1)).getComplaint(complaint.id).await().indefinitely()!!.breached).isTrue()
    }

    @Test
    fun `a resolved complaint past its due date is never breached`() {
        val due = LocalDate.of(2026, 7, 3)
        val complaint = baseComplaint(received = LocalDate.of(2026, 6, 12), due = due)
            .copy(status = ComplaintStatus.RESOLVED)
        every { repo.findById(complaint.id) } returns Uni.createFrom().item(complaint)
        val result = serviceAt(due.plusDays(10)).getComplaint(complaint.id).await().indefinitely()
        assertThat(result!!.breached).isFalse()
    }

    // ---- resolve / close transitions ----

    @Test
    fun `resolve records outcome and redress flag and sets RESOLVED`() {
        val complaint = baseComplaint()
        every { repo.findById(complaint.id) } returns Uni.createFrom().item(complaint)
        every { repo.update(any(), any()) } answers { Uni.createFrom().item(firstArg<Complaint>()) }

        val result = serviceAt(LocalDate.of(2026, 6, 20))
            .resolve(complaint.id, ResolveComplaintRequest(outcome = "Refund issued", redressGranted = true))
            .await().indefinitely()

        assertThat(result.status).isEqualTo(ComplaintStatus.RESOLVED)
        assertThat(result.outcome).isEqualTo("Refund issued")
        assertThat(result.redressGranted).isTrue()
        assertThat(result.resolvedAt).isNotNull()
    }

    @Test
    fun `close records root-cause code and redress flag and sets CLOSED`() {
        val complaint = baseComplaint()
        every { repo.findById(complaint.id) } returns Uni.createFrom().item(complaint)
        every { repo.update(any(), any()) } answers { Uni.createFrom().item(firstArg<Complaint>()) }

        val result = serviceAt(LocalDate.of(2026, 6, 20)).close(
            complaint.id,
            CloseComplaintRequest(
                outcome = "Resolved in favour of customer",
                rootCauseCode = "DUP-DEBIT",
                redressGranted = true,
            ),
        ).await().indefinitely()

        assertThat(result.status).isEqualTo(ComplaintStatus.CLOSED)
        assertThat(result.rootCauseCode).isEqualTo("DUP-DEBIT")
        assertThat(result.redressGranted).isTrue()
        assertThat(result.closedAt).isNotNull()
        assertThat(result.resolvedAt).isNotNull()
    }

    @Test
    fun `interim reply on a missing complaint fails`() {
        val id = UUID.randomUUID()
        every { repo.findById(id) } returns Uni.createFrom().item(null as Complaint?)
        val failure = runCatching {
            serviceAt(LocalDate.of(2026, 6, 20)).interimReply(id, InterimReplyRequest("x")).await().indefinitely()
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `file emits a complaint_received outbox event`() {
        val outbox = slot<OutboxMessage>()
        every { repo.save(any(), capture(outbox)) } answers { Uni.createFrom().item(firstArg<Complaint>()) }
        val result = serviceAt(LocalDate.of(2026, 6, 9)).file(fileRequest()).await().indefinitely()
        assertThat(outbox.captured.eventType).isEqualTo("complaint.received")
        assertThat(outbox.captured.aggregateId).isEqualTo(result.id)
    }

    private fun baseComplaint(
        received: LocalDate = LocalDate.of(2026, 6, 9),
        due: LocalDate = LocalDate.of(2026, 6, 30),
    ): Complaint {
        val fixedClock = Clock.fixed(
            received.atTime(10, 0).atZone(zone).toInstant(),
            zone,
        )
        val now = OffsetDateTime.now(fixedClock)
        return Complaint(
            id = UUID.randomUUID(),
            reference = "CMP-1000",
            category = ComplaintCategory.PAYMENT_SERVICE,
            channel = ComplaintChannel.APP,
            description = "test",
            receivedDate = received,
            dueDate = due,
            createdAt = now,
            updatedAt = now,
        )
    }
}
