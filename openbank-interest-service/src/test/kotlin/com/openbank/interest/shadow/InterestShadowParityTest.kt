// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.shadow

import com.openbank.interest.application.port.out.AccountDirectoryPort
import com.openbank.interest.application.port.out.InterestAccrualRepository
import com.openbank.interest.application.port.out.InterestAccrualTransaction
import com.openbank.interest.application.port.out.InterestCapitalizationRepository
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.LedgerPostingPort
import com.openbank.interest.application.port.out.TaxProfilePort
import com.openbank.interest.application.usecase.InterestService
import com.openbank.interest.application.usecase.InterestShadowComparator
import com.openbank.interest.application.usecase.InterestShadowResult
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.shadow.ShadowLegacyAccrual
import com.openbank.interest.domain.shadow.ShadowLegacyCapitalization
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0352 §10: the interest switch needs bit-exact parity between `InterestService` and the IR at the
 * scale-6 accrual AND at the currency-scale capitalisation. This drives the REAL `InterestService`
 * (its own arithmetic, its own rounding, its own capitalisation and withholding) over production-shaped
 * portfolios — balances from a few crowns to ten million, every day from 28 Dec 2027 to 2 Mar 2028 (a
 * year end, a leap-year February with its 29th, three month ends), capitalised balances fed back into
 * the next day's accrual, three tax treatments — and holds every row it writes to the IR of the
 * committed `savings-czk` reference product (and day-count / rate / currency variants of it).
 *
 * Asserts ZERO divergences. It does not tune InterestService to match: a divergence is a migration risk
 * to surface (ADR-0352 §10), and the failure message prints the first ones exactly.
 *
 * What this is not: recorded inputs exported from a running service, which ADR-0352 §10 also asks for
 * before the SWITCH. That evidence is what the shadow itself accrues in an environment; this test is the
 * pre-condition for running it.
 */
class InterestShadowParityTest {

    private val comparator = InterestShadowComparator(ProductInterpreter(FinanceProductArithmetic))

    private val balances = listOf(
        "0.01", "3.33", "1.00", "999.99", "10000.00", "25000.00", "50000.50", "250000.00", "1234567.89", "9999999.99",
    )
    private val profiles = listOf(
        TaxProfile.FAIL_SAFE_DEFAULT,
        TaxProfile(TaxpayerType.LEGAL_ENTITY, TaxResidency.RESIDENT),
        TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.NON_RESIDENT, nonCooperatingState = true),
    )
    private val from: LocalDate = LocalDate.of(2027, 12, 28)
    private val to: LocalDate = LocalDate.of(2028, 3, 2)

    private data class Scenario(val name: String, val ir: String, val legacyDayCount: DayCount, val rate: String)

    private val tieredSingleBand = ShadowFixtures.SAVINGS_CZK.replace(
        "\"rate\":\"0.0300000000\",\"rateType\":\"FIXED\"",
        "\"rateType\":\"TIERED\",\"tiers\":[{\"from\":\"0.0000\",\"rate\":\"0.0300000000\"}]",
    )

    private val scenarios = listOf(
        Scenario("reference savings-czk", ShadowFixtures.SAVINGS_CZK, DayCount.ACT_365, "0.0300"),
        Scenario("tiny rate", ShadowFixtures.withRate("0.0000100000"), DayCount.ACT_365, "0.00001"),
        Scenario("high odd rate", ShadowFixtures.withRate("0.0725000000"), DayCount.ACT_365, "0.0725"),
        Scenario("ACT/360", ShadowFixtures.withDayCount("ACT_360"), DayCount.ACT_360, "0.0300"),
        Scenario("ACT/ACT leap year", ShadowFixtures.withDayCount("ACT_ACT"), DayCount.ACT_ACT, "0.0300"),
        Scenario("30/360", ShadowFixtures.withDayCount("THIRTY_360_BOND_BASIS"), DayCount.THIRTY_360, "0.0300"),
        Scenario("single-band TIERED", tieredSingleBand, DayCount.ACT_365, "0.0300"),
        Scenario(
            "EUR, not withheld",
            ShadowFixtures.SAVINGS_CZK.replace("\"CZK\"", "\"EUR\""),
            DayCount.ACT_365,
            "0.0300",
        ),
    )

    @Test
    fun `InterestService and the savings-czk IR agree bit-exactly on every accrual and capitalisation`() {
        var accruals = 0
        var capitalisations = 0
        val diffs = ArrayList<String>()
        for (sc in scenarios) {
            val revision = ShadowFixtures.parse(sc.ir)
            val run = Legacy(revision.currency, sc.legacyDayCount, BigDecimal(sc.rate))
            balances.forEachIndexed { i, opening -> run.account(BigDecimal(opening), profiles[i % profiles.size]) }
            for (a in run.accruals) {
                accruals++
                check(sc.name, revision, legacyAccrual(a), diffs, "accrual ${a.accrualDate} balance ${a.balance}")
            }
            for (c in run.capitalisations) {
                capitalisations++
                check(sc.name, revision, legacyCap(c), diffs, "capitalisation ${c.periodTo} total ${c.totalAccrued}")
            }
        }
        // 8 scenarios x 10 accounts x 66 days, and 8 x 10 x 3 month ends (Dec, Jan, Feb).
        assertThat(accruals).isEqualTo(scenarios.size * balances.size * 66)
        assertThat(capitalisations).isEqualTo(scenarios.size * balances.size * 3)
        assertThat(diffs).describedAs("first divergences of ${diffs.size}: ${diffs.take(5)}").isEmpty()
    }

    @Test
    fun `the parity check can fail - a one-basis-point IR rate change diverges on real InterestService output`() {
        val run = Legacy("CZK", DayCount.ACT_365, BigDecimal("0.0300"))
        run.account(BigDecimal("250000.00"), TaxProfile.FAIL_SAFE_DEFAULT)
        val diffs = ArrayList<String>()
        val revision = ShadowFixtures.parse(ShadowFixtures.withRate("0.0301000000"))
        run.accruals.forEach { check("1bp", revision, legacyAccrual(it), diffs, it.accrualDate.toString()) }
        assertThat(diffs).hasSize(66)
    }

    private fun check(name: String, ir: ProductRevisionIr, legacy: Any, diffs: MutableList<String>, at: String) {
        val results = when (legacy) {
            is ShadowLegacyAccrual -> comparator.compare(ir, legacy)
            is ShadowLegacyCapitalization -> comparator.compare(ir, legacy)
            else -> error("unexpected $legacy")
        }
        for (r in results) {
            when (r) {
                is InterestShadowResult.Compared -> if (r.diverged) {
                    diffs += "[$name] $at: legacy ${r.legacyAmount.toPlainString()} vs IR ${r.irAmount.toPlainString()}"
                }
                is InterestShadowResult.Unmapped -> diffs += "[$name] $at: unmapped ${r.what}"
            }
        }
    }

    private fun legacyAccrual(a: InterestAccrual) = ShadowLegacyAccrual(
        a.id, a.accountId, a.productId, a.currency, "r", a.accrualDate, a.balance, a.accruedAmount, decidedByPin = true,
    )

    private fun legacyCap(c: InterestCapitalization) = ShadowLegacyCapitalization(
        c.id, c.accountId, c.productId, c.currency, "r", c.periodTo, c.totalAccrued, c.grossAmount, c.taxAmount,
    )

    /** One real InterestService over mocked ports, the shape InterestServiceTest uses. */
    private inner class Legacy(val currency: String, dayCount: DayCount, rate: BigDecimal) {
        val accruals = ArrayList<InterestAccrual>()
        val capitalisations = ArrayList<InterestCapitalization>()
        private val now = OffsetDateTime.parse("2028-01-01T00:00:00Z")
        private val config = InterestRateConfig(
            productId = PRODUCT,
            currency = currency,
            annualRate = rate,
            dayCount = dayCount,
            effectiveFrom = LocalDate.of(2020, 1, 1),
            createdAt = now,
            updatedAt = now,
        )
        private var profile: TaxProfile = TaxProfile.FAIL_SAFE_DEFAULT
        private var pending: List<InterestAccrual> = emptyList()
        private val configRepo = mockk<InterestRateConfigRepository>().also {
            every { it.findEffectiveRate(any(), any(), any(), any()) } returns Uni.createFrom().item(config)
        }
        private val accrualRepo = mockk<InterestAccrualRepository>().also {
            every { it.findByNaturalKey(any(), any(), any(), any()) } returns
                Uni.createFrom().item(null as InterestAccrual?)
            every { it.save(any()) } answers { Uni.createFrom().item(firstArg<InterestAccrual>().also(accruals::add)) }
            every { it.findClaimedForCapitalization(any(), any()) } returns Uni.createFrom().item(emptyList())
            every { it.findPendingCapitalization(any(), any(), any()) } answers { Uni.createFrom().item(pending) }
            every { it.claimForCapitalization(any(), any(), any()) } returns Uni.createFrom().item(Unit)
        }
        private val capRepo = mockk<InterestCapitalizationRepository>().also {
            every { it.saveWithOutbox(any(), any(), any(), any(), any()) } answers {
                Uni.createFrom().item(firstArg<InterestCapitalization>().also(capitalisations::add))
            }
        }
        private val taxes = mockk<TaxProfilePort>().also {
            every { it.resolve(any()) } answers { Uni.createFrom().item(profile) }
        }
        private val ledger = mockk<LedgerPostingPort>().also {
            every { it.post(any()) } returns Uni.createFrom().item(Unit)
        }
        private val service = InterestService(
            configRepo, accrualRepo, capRepo, taxes, ledger, mockk<AccountDirectoryPort>(), "ACT_365", "SAVINGS",
            Clock.fixed(Instant.parse("2028-01-01T00:00:00Z"), ZoneOffset.UTC),
        ).also { svc ->
            svc.accrualTransactions = mockk<InterestAccrualTransaction>().also {
                every { it.withAccrual(any()) } answers { firstArg<() -> Uni<InterestAccrual>>().invoke() }
            }
        }

        /** Accrues one account daily, capitalises at each month end, and credits the NET to the next day's balance. */
        fun account(opening: BigDecimal, taxProfile: TaxProfile) {
            val accountId = UUID.randomUUID()
            profile = taxProfile
            var balance = opening
            val period = ArrayList<InterestAccrual>()
            var day = from
            while (!day.isAfter(to)) {
                val a = service.accrue(
                    AccrualRequest(accountId, PRODUCT, balance, currency, day),
                ).await().indefinitely()
                period += a
                if (day == YearMonth.from(day).atEndOfMonth()) {
                    pending = period.toList()
                    val cap = service.capitalize(accountId, PRODUCT, day).await().indefinitely()
                    balance += cap.netAmount
                    period.clear()
                }
                day = day.plusDays(1)
            }
        }
    }

    private companion object {
        const val PRODUCT = "savings"
    }
}
