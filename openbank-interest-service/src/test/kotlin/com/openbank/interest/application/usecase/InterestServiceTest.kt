// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestCapitalizationRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.AccrualStatus
import com.openbank.interest.domain.model.AccrualSummary
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.model.InterestRateType
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.domain.tax.WithholdingTreatment
import com.openbank.libs.persistence.outbox.OutboxMessage
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

class InterestServiceTest {

    private val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val configRepo = mockk<InterestRateConfigRepository>()
    private val accrualRepo = mockk<InterestAccrualRepository>()
    private val capitalizationRepo = mockk<InterestCapitalizationRepository>()
    private val taxProfilePort = mockk<TaxProfilePort>()
    private val service = InterestService(
        configRepo,
        accrualRepo,
        capitalizationRepo,
        taxProfilePort,
        "ACT_365",
        clock,
    )

    // Captures of the single-transaction capitalization write, filled by stubCapitalization().
    private val capSlot: CapturingSlot<InterestCapitalization> = slot()
    private val whtSlot: CapturingSlot<WithholdingTax> = slot()
    private val eventSlot: CapturingSlot<OutboxMessage> = slot()
    private val accrualIdsSlot: CapturingSlot<List<UUID>> = slot()

    /**
     * Stubs the profile lookup and the ONE atomic write the use case now performs: capitalization +
     * withholding + outbox event + the guarded accrual flip all land through `saveWithOutbox`, so
     * there is nothing else for the service to call.
     */
    private fun stubCapitalization(profile: TaxProfile = TaxProfile.FAIL_SAFE_DEFAULT) {
        every { taxProfilePort.resolve(any()) } returns Uni.createFrom().item(profile)
        every {
            capitalizationRepo.saveWithOutbox(
                capture(capSlot),
                capture(whtSlot),
                capture(eventSlot),
                capture(accrualIdsSlot),
                any(),
            )
        } answers { Uni.createFrom().item(firstArg<InterestCapitalization>()) }
    }

    @Test
    fun `accrue calculates daily rate correctly`() {
        val request = AccrualRequest(
            accountId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            productId = "SAVINGS",
            balance = BigDecimal("1000.00"),
            currency = "EUR",
            accrualDate = LocalDate.of(2026, 1, 20),
        )
        val config = sampleConfig(annualRate = BigDecimal("0.365"), dayCount = DayCount.ACT_365)
        val accrualSlot: CapturingSlot<InterestAccrual> = slot()

        every {
            configRepo.findActiveForProduct(request.productId, request.accrualDate)
        } returns Uni.createFrom().item(config)
        every { accrualRepo.save(capture(accrualSlot)) } returns
            Uni.createFrom().item(expectedAccrual(request, config))

        val result = service.accrue(request).await().indefinitely()

        assertThat(accrualSlot.captured.dailyRate).isEqualByComparingTo(BigDecimal("0.0010000000"))
        assertThat(accrualSlot.captured.accruedAmount).isEqualByComparingTo(BigDecimal("1.000000"))
        assertThat(result).isEqualTo(expectedAccrual(request, config))
        verify(exactly = 1) { configRepo.findActiveForProduct(request.productId, request.accrualDate) }
        verify(exactly = 1) { accrualRepo.save(any()) }
    }

    @Test
    fun `accrue throws when no config found`() {
        val request = AccrualRequest(
            accountId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            productId = "SAVINGS",
            balance = BigDecimal("1000.00"),
            currency = "EUR",
            accrualDate = LocalDate.of(2026, 1, 20),
        )

        every {
            configRepo.findActiveForProduct(request.productId, request.accrualDate)
        } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.accrue(request).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("No active rate config for product SAVINGS")
        verify(exactly = 0) { accrualRepo.save(any()) }
    }

    @Test
    fun `capitalize sums accruals and credits gross for non-CZK (deferred withholding)`() {
        val accountId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val productId = "SAVINGS"
        val toDate = LocalDate.of(2026, 1, 20)
        // EUR interest: §38 conversion not in v1 scope -> DEFERRED_FX, credited gross (ADR-0033 §E).
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("1.20")),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("2.30")),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        val result = service.capitalize(accountId, productId, toDate).await().indefinitely()

        assertThat(capSlot.captured.totalAccrued).isEqualByComparingTo(BigDecimal("3.50"))
        assertThat(capSlot.captured.grossAmount).isEqualByComparingTo(BigDecimal("3.5000"))
        assertThat(capSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("0"))
        assertThat(capSlot.captured.capitalizedAmount).isEqualByComparingTo(BigDecimal("3.5000"))
        assertThat(capSlot.captured.periodFrom).isEqualTo(LocalDate.of(2026, 1, 18))
        assertThat(capSlot.captured.periodTo).isEqualTo(toDate)
        assertThat(whtSlot.captured.treatment).isEqualTo(WithholdingTreatment.DEFERRED_FX)
        assertThat(whtSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.capitalizedAmount).isEqualByComparingTo(BigDecimal("3.5000"))
        // The product filter is part of the query, not a post-filter: the repo must never be asked
        // for an account's accruals across products.
        verify(exactly = 1) { accrualRepo.findPendingCapitalization(accountId, productId, toDate) }
    }

    @Test
    fun `capitalize withholds 15 percent and credits net on CZK interest`() {
        val accountId = UUID.fromString("88888888-8888-8888-8888-888888888888")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "CZK"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("40.00"), currency = "CZK"),
        )
        stubCapitalization() // resident individual default -> 15 %

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        val result = service.capitalize(accountId, productId, toDate).await().indefinitely()

        // gross 100.00 CZK -> base 100, tax 15, net 85.
        assertThat(capSlot.captured.grossAmount).isEqualByComparingTo(BigDecimal("100.0000"))
        assertThat(capSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(capSlot.captured.netAmount).isEqualByComparingTo(BigDecimal("85.0000"))
        assertThat(capSlot.captured.capitalizedAmount).isEqualByComparingTo(BigDecimal("85.0000"))
        assertThat(whtSlot.captured.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(whtSlot.captured.rate).isEqualByComparingTo(BigDecimal("0.15"))
        assertThat(whtSlot.captured.taxableBase).isEqualByComparingTo(BigDecimal("100"))
        assertThat(whtSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(whtSlot.captured.capitalizationId).isEqualTo(capSlot.captured.id)
        assertThat(result.capitalizedAmount).isEqualByComparingTo(BigDecimal("85.0000"))
    }

    @Test
    fun `capitalization, withholding, event and accrual flip are handed to ONE transaction`() {
        val accountId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "CZK"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("40.00"), currency = "CZK"),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Exactly one write call: the four legs of a credit can no longer be committed separately.
        verify(exactly = 1) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
        // ...and the accrual flip is part of that same call, carrying every source accrual.
        assertThat(accrualIdsSlot.captured).containsExactlyElementsOf(accruals.map { it.id })
        assertThat(eventSlot.captured.eventType).isEqualTo("interest.withholding.recorded.v1")
        assertThat(eventSlot.captured.aggregateId).isEqualTo(capSlot.captured.id)
        assertThat(eventSlot.captured.payload).contains("\"taxAmount\":\"15\"")
    }

    @Test
    fun `a failing single transaction surfaces the failure and writes nothing else`() {
        val accountId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "CZK"),
        )

        every { taxProfilePort.resolve(any()) } returns Uni.createFrom().item(TaxProfile.FAIL_SAFE_DEFAULT)
        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)
        // Mirrors the repo's status guard tripping (a concurrent run flipped the accruals first):
        // the whole write set rolls back, so the use case must propagate, not swallow-and-continue.
        every { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) } returns
            Uni.createFrom().failure(IllegalStateException("Capitalization aborted: expected to flip 1, matched 0"))

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Capitalization aborted")
    }

    @Test
    fun `capitalize refuses a mixed-currency accrual set instead of summing it`() {
        val accountId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val productId = "SAVINGS"
        val toDate = LocalDate.of(2026, 1, 20)
        // 100 CZK + 5 EUR is not "105" of anything, and only the CZK leg is withholdable (§E).
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("100.00"), currency = "CZK"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("5.00"), currency = "EUR"),
        )

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("mixed-currency")
            .hasMessageContaining("[CZK, EUR]")
        // Nothing is credited, nothing is withheld, and the accruals stay ACCRUING for an operator.
        verify(exactly = 0) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { taxProfilePort.resolve(any()) }
    }

    @Test
    fun `a single currency in mixed casing is one currency, not a mixed set`() {
        val accountId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        val productId = "SAVINGS_CZK"
        val toDate = LocalDate.of(2026, 1, 20)
        val accruals = listOf(
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 18), BigDecimal("60.00"), currency = "czk"),
            sampleAccrual(accountId, productId, LocalDate.of(2026, 1, 19), BigDecimal("40.00"), currency = "CZK"),
        )
        stubCapitalization()

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(accruals)

        service.capitalize(accountId, productId, toDate).await().indefinitely()

        // Normalized to the canonical code, and still withheld as CZK interest.
        assertThat(capSlot.captured.currency).isEqualTo("CZK")
        assertThat(whtSlot.captured.currency).isEqualTo("CZK")
        assertThat(whtSlot.captured.treatment).isEqualTo(WithholdingTreatment.WITHHELD)
        assertThat(capSlot.captured.taxAmount).isEqualByComparingTo(BigDecimal("15"))
    }

    @Test
    fun `capitalize throws when no pending accruals`() {
        val accountId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val productId = "SAVINGS"
        val toDate = LocalDate.of(2026, 1, 20)

        every { accrualRepo.findPendingCapitalization(accountId, productId, toDate) } returns
            Uni.createFrom().item(emptyList())

        assertThatThrownBy { service.capitalize(accountId, productId, toDate).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("No pending accruals to capitalize")
        verify(exactly = 0) { capitalizationRepo.saveWithOutbox(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getSummary returns correct totals`() {
        val accountId = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 1, 31)
        val accruals = listOf(
            sampleAccrual(accountId, "SAVINGS", LocalDate.of(2026, 1, 5), BigDecimal("1.25")),
            sampleAccrual(accountId, "SAVINGS", LocalDate.of(2026, 1, 10), BigDecimal("2.75")),
        )

        every { accrualRepo.findByAccountId(accountId, from, to) } returns Uni.createFrom().item(accruals)

        val result = service.getSummary(accountId, from, to).await().indefinitely()

        assertThat(result).isEqualTo(
            AccrualSummary(
                accountId = accountId,
                totalAccrued = BigDecimal("4.00"),
                currency = "EUR",
                fromDate = from,
                toDate = to,
                accrualCount = 2,
            ),
        )
        verify(exactly = 1) { accrualRepo.findByAccountId(accountId, from, to) }
    }

    @Test
    fun `deactivateConfig throws when not found`() {
        val id = UUID.fromString("66666666-6666-6666-6666-666666666666")

        every { configRepo.findById(id) } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.deactivateConfig(id).await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Config not found")
        verify(exactly = 0) { configRepo.update(any()) }
    }

    @Test
    fun `getAccruals delegates to repo`() {
        val accountId = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 1, 31)
        val accruals = listOf(sampleAccrual(accountId, "SAVINGS", LocalDate.of(2026, 1, 15), BigDecimal("1.11")))

        every { accrualRepo.findByAccountId(accountId, from, to) } returns Uni.createFrom().item(accruals)

        val result = service.getAccruals(accountId, from, to).await().indefinitely()

        assertThat(result).isEqualTo(accruals)
        verify(exactly = 1) { accrualRepo.findByAccountId(accountId, from, to) }
    }

    private fun sampleConfig(annualRate: BigDecimal, dayCount: DayCount = DayCount.ACT_365) = InterestRateConfig(
        id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        productId = "SAVINGS",
        rateType = InterestRateType.FIXED,
        annualRate = annualRate,
        minBalance = BigDecimal.ZERO,
        maxBalance = BigDecimal("1000000.00"),
        dayCount = dayCount,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        active = true,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    private fun expectedAccrual(request: AccrualRequest, config: InterestRateConfig) = InterestAccrual(
        id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        accountId = request.accountId,
        productId = request.productId,
        configId = config.id,
        accrualDate = request.accrualDate,
        balance = request.balance,
        dailyRate = BigDecimal("0.0010000000"),
        accruedAmount = BigDecimal("1.000000"),
        currency = request.currency,
        status = AccrualStatus.ACCRUING,
        capitalizedAt = null,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    private fun sampleAccrual(
        accountId: UUID,
        productId: String,
        accrualDate: LocalDate,
        accruedAmount: BigDecimal,
        currency: String = "EUR",
    ) = InterestAccrual(
        id = UUID.randomUUID(),
        accountId = accountId,
        productId = productId,
        configId = UUID.randomUUID(),
        accrualDate = accrualDate,
        balance = BigDecimal("1000.00"),
        dailyRate = BigDecimal("0.0010000000"),
        accruedAmount = accruedAmount,
        currency = currency,
        status = AccrualStatus.ACCRUING,
        capitalizedAt = null,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )
}
