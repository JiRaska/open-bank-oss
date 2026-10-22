// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.out.AccountDirectoryPort
import com.openbank.interest.application.port.out.AccountPage
import com.openbank.interest.application.port.out.AccountSnapshot
import com.openbank.interest.application.port.out.BalanceSnapshot
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestAccrualTransaction
import com.openbank.interest.application.port.out.InterestCapitalizationRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.LedgerPostingPort
import com.openbank.interest.application.port.out.RateRevisionPinRepository
import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.application.usecase.InterestService
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.infrastructure.observability.InterestCapitalizationMetrics
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0341/0342: an account whose catalog pin cannot be resolved accrued NOTHING, so the run is
 * incomplete and the day is reported REFUSED - the ledger must hold the day, not close it without
 * that account's interest. Driven through the real service and the real scheduler.
 */
class InterestAccrualUnresolvablePinTest {
    private val clock = Clock.fixed(Instant.parse("2027-02-10T10:00:00Z"), ZoneOffset.UTC)
    private val account = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val registry = SimpleMeterRegistry()
    private val configRepo = mockk<InterestRateConfigRepository>()
    private val accrualRepo = mockk<InterestAccrualRepository>()
    private val pins = mockk<RateRevisionPinRepository>()
    private val directory = mockk<AccountDirectoryPort>()
    private val runs = RecordingCloseJobRuns()

    private fun service(): InterestService {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return InterestService(
            configRepo,
            accrualRepo,
            mockk<InterestCapitalizationRepository>(),
            mockk<TaxProfilePort>(),
            mockk<LedgerPostingPort>(),
            directory,
            "ACT_365",
            "SAVINGS",
            clock,
        ).also {
            it.revisionPins = pins
            it.accrualTransactions = mockk<InterestAccrualTransaction>().also { tx ->
                every { tx.withAccrual(any()) } answers { firstArg<() -> Uni<InterestAccrual>>().invoke() }
            }
            it.capitalizationMetrics = InterestCapitalizationMetrics(instance)
        }
    }

    private fun counter(name: String) = registry.find(name).counter()?.count() ?: 0.0

    @Test
    fun `an unresolvable pin makes the run incomplete, counts once and records a refused close job`() {
        val now = OffsetDateTime.now(clock)
        val effective = InterestRateConfig(
            productId = "SAVINGS",
            currency = "EUR",
            annualRate = BigDecimal("0.03"),
            dayCount = DayCount.ACT_365,
            effectiveFrom = LocalDate.of(2027, 1, 1),
            createdAt = now,
            updatedAt = now,
        )
        val date = LocalDate.of(2027, 2, 10)
        every { directory.listActiveAccounts(null, any()) } returns Uni.createFrom().item(
            AccountPage(listOf(AccountSnapshot(account, "SAVINGS", "SAVINGS", "EUR")), null),
        )
        every { directory.bookedBalance(account) } returns
            Uni.createFrom().item(BalanceSnapshot(BigDecimal("1000.00"), "EUR"))
        every { accrualRepo.findByNaturalKey(any(), any(), any(), any()) } returns Uni.createFrom().nullItem()
        every { accrualRepo.save(any()) } answers { Uni.createFrom().item(firstArg<InterestAccrual>()) }
        every { configRepo.findEffectiveRate(account, "SAVINGS", any(), "EUR") } returns
            Uni.createFrom().item(effective)
        // A pin ROW exists but its revision's snapshot is gone.
        every { pins.findPinnedConfig(account, "SAVINGS", "EUR") } returns Uni.createFrom().nullItem()
        every { pins.hasPin(account, "SAVINGS", "EUR") } returns Uni.createFrom().item(true)

        val outcome = service().accrueAllWithOutcome(date).await().indefinitely()

        assertThat(outcome.complete).isFalse()
        assertThat(outcome.failed).isEqualTo(1)
        assertThat(outcome.accrued).isEqualTo(0)
        assertThat(outcome.firstFailure).contains(account.toString())
        assertThat(counter("openbank.interest.accrual.catalog.revision.unresolved")).isEqualTo(1.0)
        assertThat(counter("openbank.interest.accrual.catalog.rate.unpinned")).isEqualTo(0.0)

        // The scheduler reports that outcome to the close-job table as refused.
        val noRegistry = mockk<Instance<MeterRegistry>>()
        every { noRegistry.isResolvable } returns false
        val metrics = DomainMetrics().apply { registryInstance = noRegistry }
        InterestAccrualScheduler(service(), clock, metrics, runs).runDailyAccrual().await().indefinitely()
        val run = runs.runs.getValue(date to InterestCloseJobs.ACCRUAL)
        assertThat(run.refused).isGreaterThan(0)
        assertThat(run.refusalReason).startsWith("ACCRUAL_INCOMPLETE")
    }
}
