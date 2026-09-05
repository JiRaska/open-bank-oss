// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.InterestEventOutbox
import com.openbank.interest.application.port.out.WithholdingRemittanceRepository
import com.openbank.interest.application.port.out.WithholdingTaxRepository
import com.openbank.interest.domain.tax.WithholdingRemittance
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.domain.tax.WithholdingTaxStatus
import com.openbank.interest.domain.tax.WithholdingTreatment
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.audit.AuditEventTime
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class WithholdingRemittanceServiceTest {

    private val withholdingTaxRepo = mockk<WithholdingTaxRepository>()
    private val remittanceRepo = mockk<WithholdingRemittanceRepository>()
    private val eventOutbox = mockk<InterestEventOutbox>()
    private val service = WithholdingRemittanceService(withholdingTaxRepo, remittanceRepo, eventOutbox)

    /**
     * A FIXED clock, and the service built on its four-arg constructor so the assembly instant is
     * knowable (#8352). The other tests in this file use the `@Inject` three-arg constructor, which
     * takes `Clock.systemUTC()` — fine while nothing asserts a time, useless the moment one does.
     */
    private val fixedClock = Clock.fixed(Instant.parse("2026-02-03T09:15:00Z"), ZoneOffset.UTC)
    private val clockedService =
        WithholdingRemittanceService(withholdingTaxRepo, remittanceRepo, eventOutbox, fixedClock)

    private fun withheld(taxAmount: BigDecimal, periodTo: LocalDate = LocalDate.of(2026, 1, 31)) = WithholdingTax(
        id = UUID.randomUUID(),
        capitalizationId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        periodFrom = periodTo.withDayOfMonth(1),
        periodTo = periodTo,
        taxableBase = BigDecimal("100"),
        rate = BigDecimal("0.15"),
        taxAmount = taxAmount,
        currency = "CZK",
        treatment = WithholdingTreatment.WITHHELD,
        status = WithholdingTaxStatus.RECORDED,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `assembles a new period, marks records remitted and emits the event`() {
        val records = listOf(withheld(BigDecimal("15")), withheld(BigDecimal("85")))
        val savedSlot: CapturingSlot<WithholdingRemittance> = slot()
        val markedIds: CapturingSlot<List<UUID>> = slot()
        val eventSlot: CapturingSlot<OutboxMessage> = slot()

        every { remittanceRepo.findByPeriod(2026, 1) } returns Uni.createFrom().nullItem()
        every {
            withholdingTaxRepo.findRecordedForPeriod(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
            )
        } returns Uni.createFrom().item(records)
        every { remittanceRepo.save(capture(savedSlot)) } answers {
            Uni.createFrom().item(firstArg<WithholdingRemittance>())
        }
        every { withholdingTaxRepo.markRemitted(capture(markedIds), any()) } returns
            Uni.createFrom().item(2)
        every { eventOutbox.append(capture(eventSlot)) } returns Uni.createFrom().nullItem()

        val result = service.assembleRemittance(2026, 1).await().indefinitely()

        assertThat(result.totalTaxAmount).isEqualByComparingTo("100")
        assertThat(result.itemCount).isEqualTo(2)
        assertThat(result.dueDate).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(markedIds.captured).containsExactlyInAnyOrderElementsOf(records.map { it.id })
        assertThat(eventSlot.captured.eventType).isEqualTo("interest.withholding.remitted.v1")
        assertThat(eventSlot.captured.payload)
            .contains("\"totalTaxAmount\":\"100\"")
            .contains("\"itemCount\":2")
        verify(exactly = 1) { remittanceRepo.save(any()) }
        verify(exactly = 1) { withholdingTaxRepo.markRemitted(any(), any()) }
        verify(exactly = 1) { eventOutbox.append(any()) }
    }

    @Test
    fun `a short RECORDED to REMITTED mark fails the assembly instead of emitting the batch`() {
        val records = listOf(withheld(BigDecimal("15")), withheld(BigDecimal("85")))

        every { remittanceRepo.findByPeriod(2026, 1) } returns Uni.createFrom().nullItem()
        every {
            withholdingTaxRepo.findRecordedForPeriod(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
            )
        } returns Uni.createFrom().item(records)
        every { remittanceRepo.save(any()) } answers { Uni.createFrom().item(firstArg<WithholdingRemittance>()) }
        // The status-guarded UPDATE matched only one of the two rows — a concurrent assembly (or a
        // reversal) took the other. The assembled totals no longer describe what was marked.
        every { withholdingTaxRepo.markRemitted(any(), any()) } returns Uni.createFrom().item(1)

        assertThatThrownBy { service.assembleRemittance(2026, 1).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("expected to mark 2 RECORDED withholdings REMITTED, matched 1")

        // Crucially: no event, so the rail is never asked to pay an unsubstantiated figure.
        verify(exactly = 0) { eventOutbox.append(any()) }
    }

    @Test
    fun `is idempotent - an existing batch is returned without re-marking or re-emitting`() {
        val existing = WithholdingRemittance(
            periodYear = 2026,
            periodMonth = 1,
            totalTaxAmount = BigDecimal("100"),
            itemCount = 2,
            dueDate = LocalDate.of(2026, 2, 28),
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )
        every { remittanceRepo.findByPeriod(2026, 1) } returns Uni.createFrom().item(existing)

        val result = service.assembleRemittance(2026, 1).await().indefinitely()

        assertThat(result).isEqualTo(existing)
        verify(exactly = 0) { withholdingTaxRepo.findRecordedForPeriod(any(), any()) }
        verify(exactly = 0) { remittanceRepo.save(any()) }
        verify(exactly = 0) { withholdingTaxRepo.markRemitted(any(), any()) }
        verify(exactly = 0) { eventOutbox.append(any()) }
    }

    @Test
    fun `a nil period still assembles a zero batch and emits`() {
        val savedSlot: CapturingSlot<WithholdingRemittance> = slot()
        every { remittanceRepo.findByPeriod(2026, 3) } returns Uni.createFrom().nullItem()
        every {
            withholdingTaxRepo.findRecordedForPeriod(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
            )
        } returns Uni.createFrom().item(emptyList())
        every { remittanceRepo.save(capture(savedSlot)) } answers {
            Uni.createFrom().item(firstArg<WithholdingRemittance>())
        }
        every { withholdingTaxRepo.markRemitted(emptyList(), any()) } returns Uni.createFrom().item(0)
        every { eventOutbox.append(any()) } returns Uni.createFrom().nullItem()

        val result = service.assembleRemittance(2026, 3).await().indefinitely()

        assertThat(result.itemCount).isEqualTo(0)
        assertThat(result.totalTaxAmount).isEqualByComparingTo("0")
        verify(exactly = 1) { remittanceRepo.save(any()) }
        verify(exactly = 1) { eventOutbox.append(any()) }
    }

    /**
     * #8352: red against `origin/main`, where this payload carried no event time of any name.
     *
     * `dueDate` is the field that looks like an answer and is not: a `LocalDate` regulatory
     * deadline is the date the obligation must be met BY, not a moment anything happened. So
     * `AuditConsumer.eventTime` found nothing and every audit row for a tax remittance recorded
     * the audit consumer's ingest clock as the assembly time.
     *
     * The instant asserted is the batch's own `createdAt` — stamped by [assembleRemittance] from
     * the injected clock as it built this batch, which IS the event. Asserting the exact value
     * rather than mere presence is what separates the fix from a fresh `Instant.now()` at
     * serialisation, which would look identical in the audit trail and measure nothing.
     */
    @Test
    fun `the remitted payload carries the assembly instant as the audit event time`() {
        val records = listOf(withheld(BigDecimal("15")))
        val savedSlot: CapturingSlot<WithholdingRemittance> = slot()
        val eventSlot: CapturingSlot<OutboxMessage> = slot()

        every { remittanceRepo.findByPeriod(2026, 1) } returns Uni.createFrom().nullItem()
        every {
            withholdingTaxRepo.findRecordedForPeriod(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
            )
        } returns Uni.createFrom().item(records)
        every { remittanceRepo.save(capture(savedSlot)) } answers {
            Uni.createFrom().item(firstArg<WithholdingRemittance>())
        }
        every { withholdingTaxRepo.markRemitted(any(), any()) } returns Uni.createFrom().item(1)
        every { eventOutbox.append(capture(eventSlot)) } returns Uni.createFrom().nullItem()

        clockedService.assembleRemittance(2026, 1).await().indefinitely()

        // Not `fixedClock.instant()` directly: the assertion has to be the instant the batch
        // actually recorded, or it would still pass against a payload that read a second clock.
        AuditEventTime.assertRecordedAsEventTime(
            eventSlot.captured.payload,
            savedSlot.captured.createdAt.toInstant(),
        )
        assertThat(savedSlot.captured.createdAt.toInstant()).isEqualTo(fixedClock.instant())
    }
}
