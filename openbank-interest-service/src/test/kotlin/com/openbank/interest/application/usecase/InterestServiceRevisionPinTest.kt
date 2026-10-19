// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.AccountDirectoryPort
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestCapitalizationRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.LedgerPostingPort
import com.openbank.interest.application.port.out.RateRevisionPinRepository
import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.CatalogRevisionUnresolvableException
import com.openbank.interest.domain.model.CatalogSourcedRateException
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestRateConfig
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

/** ADR-0341: an account accrues at the rate of the revision it is pinned to, not the currently effective one. */
class InterestServiceRevisionPinTest {
    private val clock = Clock.fixed(Instant.parse("2027-02-10T00:00:00Z"), ZoneOffset.UTC)
    private val configRepo = mockk<InterestRateConfigRepository>()
    private val accrualRepo = mockk<InterestAccrualRepository>().also { repository ->
        every { repository.withAccrual(any()) } answers { firstArg<() -> Uni<InterestAccrual>>().invoke() }
    }
    private val pins = mockk<RateRevisionPinRepository>()
    private val service = InterestService(
        configRepo,
        accrualRepo,
        mockk<InterestCapitalizationRepository>(),
        mockk<TaxProfilePort>(),
        mockk<LedgerPostingPort>(),
        mockk<AccountDirectoryPort>(),
        "ACT_365",
        "SAVINGS",
        clock,
    ).also { it.revisionPins = pins }

    private val account = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val request = AccrualRequest(account, "SAVINGS", BigDecimal("1000.00"), "EUR", LocalDate.of(2027, 2, 10))
    private val now = OffsetDateTime.now(clock)

    private fun config(rate: String, accountId: UUID? = null) = InterestRateConfig(
        productId = "SAVINGS",
        accountId = accountId,
        currency = "EUR",
        annualRate = BigDecimal(rate),
        dayCount = DayCount.ACT_365,
        effectiveFrom = LocalDate.of(2027, 1, 1),
        createdAt = now,
        updatedAt = now,
    )

    private fun stubAccrual(): CapturingSlot<InterestAccrual> {
        val saved = slot<InterestAccrual>()
        every { accrualRepo.findByNaturalKey(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { accrualRepo.save(capture(saved)) } answers { Uni.createFrom().item(firstArg<InterestAccrual>()) }
        return saved
    }

    @Test
    fun `pinned account keeps the pinned revision rate when a superseding revision is effective`() {
        val pinned = config("0.02")
        val superseding = config("0.03")
        val saved = stubAccrual()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(superseding)
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(pinned)

        service.accrue(request).await().indefinitely()

        assertThat(saved.captured.configId).isEqualTo(pinned.id)
        verify(exactly = 0) { pins.pinIfCatalogSourced(any(), any(), any(), any()) }
    }

    @Test
    fun `unpinned account is pinned to the catalog-sourced effective revision on first accrual`() {
        val effective = config("0.02")
        val saved = stubAccrual()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(effective)
        every { pins.hasPin(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.hasCatalogHistory(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returnsMany
            listOf(Uni.createFrom().nullItem(), Uni.createFrom().item(effective))
        every { pins.pinIfCatalogSourced(account, "SAVINGS", "EUR", effective) } returns Uni.createFrom().item(true)

        service.accrue(request).await().indefinitely()

        assertThat(saved.captured.configId).isEqualTo(effective.id)
        verify(exactly = 1) { pins.pinIfCatalogSourced(account, "SAVINGS", "EUR", effective) }
    }

    @Test
    fun `operator account override wins over the pin`() {
        val override = config("0.05", accountId = account)
        val saved = stubAccrual()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(override)

        service.accrue(request).await().indefinitely()

        assertThat(saved.captured.configId).isEqualTo(override.id)
        verify(exactly = 0) { pins.findPinnedConfig(any(), any(), any()) }
    }

    @Test
    fun `deactivating a catalog-sourced rate config is refused and writes nothing`() {
        val cached = config("0.02")
        every { configRepo.findById(cached.id) } returns Uni.createFrom().item(cached)
        every { pins.isCatalogSourced(cached.id) } returns Uni.createFrom().item(true)

        assertThatThrownBy { service.deactivateConfig(cached.id).await().indefinitely() }
            .isInstanceOf(CatalogSourcedRateException::class.java)
        verify(exactly = 0) { configRepo.updateWithOutbox(any(), any()) }
    }

    @Test
    fun `a pin that cannot be resolved fails the accrual and never falls back to the effective rate`() {
        val effective = config("0.03")
        every { accrualRepo.findByNaturalKey(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(effective)
        every { pins.hasPin(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.hasCatalogHistory(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returns Uni.createFrom().nullItem()
        every { pins.pinIfCatalogSourced(account, "SAVINGS", "EUR", effective) } returns Uni.createFrom().item(true)

        assertThatThrownBy { service.accrue(request).await().indefinitely() }
            .isInstanceOf(CatalogRevisionUnresolvableException::class.java)
        verify(exactly = 0) { accrualRepo.save(any()) }
    }

    @Test
    fun `no pin written means an operator-managed rate and the effective config answers`() {
        val effective = config("0.03")
        val saved = stubAccrual()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(effective)
        every { pins.hasPin(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.hasCatalogHistory(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.isCatalogSourced(effective.id) } returns Uni.createFrom().item(false)
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returns Uni.createFrom().nullItem()
        every { pins.pinIfCatalogSourced(account, "SAVINGS", "EUR", effective) } returns Uni.createFrom().item(false)

        service.accrue(request).await().indefinitely()

        assertThat(saved.captured.configId).isEqualTo(effective.id)
    }

    @Test
    fun `an existing pin whose snapshot is missing fails and never reaches the effective rate`() {
        val effective = config("0.03")
        every { accrualRepo.findByNaturalKey(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(effective)
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returns Uni.createFrom().nullItem()
        every { pins.hasPin(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(true)

        assertThatThrownBy { service.accrue(request).await().indefinitely() }
            .isInstanceOf(CatalogRevisionUnresolvableException::class.java)
        verify(exactly = 0) { pins.pinIfCatalogSourced(any(), any(), any(), any()) }
        verify(exactly = 0) { accrualRepo.save(any()) }
    }

    @Test
    fun `catalog history without a pin fails closed instead of accruing at the current catalog rate`() {
        val effective = config("0.03")
        every { accrualRepo.findByNaturalKey(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(effective)
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returns Uni.createFrom().nullItem()
        every { pins.hasPin(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.hasCatalogHistory(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(true)

        assertThatThrownBy { service.accrue(request).await().indefinitely() }
            .isInstanceOf(CatalogRevisionUnresolvableException::class.java)
        verify(exactly = 0) { pins.pinIfCatalogSourced(any(), any(), any(), any()) }
        verify(exactly = 0) { accrualRepo.save(any()) }
    }

    @Test
    fun `a catalog-sourced effective rate with no resolvable pin fails closed even when nothing was written`() {
        val effective = config("0.03")
        every { accrualRepo.findByNaturalKey(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { configRepo.findEffectiveRate(account, "SAVINGS", request.accrualDate, "EUR") } returns
            Uni.createFrom().item(effective)
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returns Uni.createFrom().nullItem()
        every { pins.hasPin(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.hasCatalogHistory(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(false)
        every { pins.pinIfCatalogSourced(account, "SAVINGS", "EUR", effective) } returns Uni.createFrom().item(false)
        every { pins.isCatalogSourced(effective.id) } returns Uni.createFrom().item(true)

        assertThatThrownBy { service.accrue(request).await().indefinitely() }
            .isInstanceOf(CatalogRevisionUnresolvableException::class.java)
        verify(exactly = 0) { accrualRepo.save(any()) }
    }
}
