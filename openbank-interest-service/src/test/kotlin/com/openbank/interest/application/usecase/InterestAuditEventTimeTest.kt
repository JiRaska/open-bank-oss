// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.AccountDirectoryPort
import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestCapitalizationRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.LedgerPostingPort
import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.audit.AuditEventTime
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What `interest.withholding.recorded.v1` becomes in the audit trail (#8352).
 *
 * A file of its own rather than another method on `InterestServiceTest`: that class is already at
 * detekt's `LargeClass` threshold, so the honest place for a new concern is a new class, and the
 * concern here IS distinct — every other test in that file is about the arithmetic and the
 * transaction boundary, none about what the payload asserts to a consumer.
 */
class InterestAuditEventTimeTest {

    private val clock = Clock.fixed(Instant.parse("2026-02-01T11:30:00Z"), ZoneOffset.UTC)
    private val configRepo = mockk<InterestRateConfigRepository>()
    private val accrualRepo = mockk<InterestAccrualRepository>()
    private val capitalizationRepo = mockk<InterestCapitalizationRepository>()
    private val taxProfilePort = mockk<TaxProfilePort>()
    private val ledgerPostingPort = mockk<LedgerPostingPort>()
    private val accountDirectoryPort = mockk<AccountDirectoryPort>()
    private val service = InterestService(
        configRepo,
        accrualRepo,
        capitalizationRepo,
        taxProfilePort,
        ledgerPostingPort,
        accountDirectoryPort,
        "ACT_365",
        "SAVINGS",
        clock,
    )

    private val whtSlot: CapturingSlot<WithholdingTax> = slot()
    private val eventSlot: CapturingSlot<OutboxMessage> = slot()

    /**
     * #8352: red against `origin/main`, where this payload carried no event time of any name.
     *
     * `periodFrom`/`periodTo` look like the answer and are not — they are `LocalDate` accrual-period
     * bounds, the range the tax was computed OVER, not a moment anything happened. So
     * `AuditConsumer.eventTime` (which reads `occurredAt` and only `occurredAt`) found nothing, and
     * every audit row for a withholding decision recorded the audit consumer's ingest clock as the
     * moment tax was withheld from a customer.
     *
     * The instant asserted is the withholding row's own `createdAt` — the value this use case
     * already stamps from the injected clock inside the very transaction the event announces, and
     * the same one it hands `saveWithOutbox` as `capitalizedAt`. Asserting THAT, and not merely
     * that some event time is present, is the point: a fresh `Instant.now()` taken at serialisation
     * would satisfy presence while asserting a business time nothing measured — indistinguishable
     * in the trail from a real one, and so worse than an honest INGEST row.
     */
    @Test
    fun `the withholding-recorded payload carries the recording instant as the audit event time`() {
        val accountId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)

        every { accrualRepo.findClaimedForCapitalization(any(), any()) } returns Uni.createFrom().item(emptyList())
        every { accrualRepo.claimForCapitalization(any(), any(), any()) } returns Uni.createFrom().item(Unit)
        every { taxProfilePort.resolve(any()) } returns Uni.createFrom().item(TaxProfile.FAIL_SAFE_DEFAULT)
        every { ledgerPostingPort.post(any<CapitalizationPosting>()) } returns Uni.createFrom().item(Unit)
        every {
            capitalizationRepo.saveWithOutbox(any(), capture(whtSlot), capture(eventSlot), any(), any())
        } answers { Uni.createFrom().item(firstArg<InterestCapitalization>()) }
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(listOf(accrual(accountId, productId)))

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        AuditEventTime.assertRecordedAsEventTime(
            eventSlot.captured.payload,
            whtSlot.captured.createdAt.toInstant(),
        )
    }

    private fun accrual(accountId: UUID, productId: String) = InterestAccrual(
        id = UUID.randomUUID(),
        accountId = accountId,
        productId = productId,
        configId = UUID.randomUUID(),
        accrualDate = LocalDate.of(2026, 1, 18),
        balance = BigDecimal("1000.00"),
        dailyRate = BigDecimal("0.0010000000"),
        accruedAmount = BigDecimal("60.00"),
        currency = "CZK",
        status = AccrualStatus.ACCRUING,
        capitalizedAt = null,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )
}
