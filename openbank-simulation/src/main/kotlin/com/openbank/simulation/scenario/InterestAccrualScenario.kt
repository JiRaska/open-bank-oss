// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.interest.domain.tax.WithholdingTaxPolicy
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Issue #667 (E2E money-path): one step of the credit-interest path — daily accrual, period
 * capitalization with the REAL statutory withholding split, and the balanced capitalization
 * journal — modelled directly against the **real `openbank-interest-service` domain**
 * (`InterestRateConfig`/`InterestAccrual`/`InterestCapitalization`, and above all
 * `WithholdingTaxPolicy` — the ADR-0033 §36/§38d srážková daň with its whole-CZK statutory
 * rounding) and the **real `openbank-ledger-service` `JournalEntry`** aggregate, mirroring
 * [PaymentScenario]/[SepaSettlementScenario]'s "build on the real system" convention (ADR-0100).
 *
 * The accrual arithmetic (`annualRate / dayCount` at scale 10, `balance × dailyRate` at scale 6)
 * is `InterestService.accrue`'s exact formula — the use case itself is Mutiny/CDI-bound and
 * cannot run inside the pure-JVM harness, so the scenario replicates the two-line computation
 * and drives everything downstream of it through the real domain types.
 *
 * The journal is the ADR-0033 §D posting shape: DEBIT interest-expense GL (gross), CREDIT the
 * deposit-control GL with `subAccountId = accountId` (net — the amount the customer actually
 * receives), and, when tax was withheld, CREDIT the withholding-tax-payable GL (tax). Statutory
 * rounding happens INSIDE `WithholdingTaxPolicy`, so `gross == net + tax` must hold to the
 * haléř — `MoneyPathInvariants.interestCapitalizationConservation` asserts exactly that, plus
 * that every capitalized amount actually landed in the ledger.
 *
 * Dispatch models the interest-service template (compute → outbox → dispatcher, ADR-0143's
 * "deferred-posting template"): a seeded write fault fails the FIRST dispatch attempt and the
 * outbox redrive — scheduled on the deterministic scheduler, executed by the same step's
 * drain — lands the identical entry under the identical idempotency key, so the invariant sweep
 * only ever sees the settled state (mirrors [FeeBillingScenario]-era "settled, not mid-flight"
 * semantics).
 */
object InterestAccrualScenario {

    private val ZONE = ZoneId.of("Europe/Prague")

    // Customer deposit positions live on the SAME deposit-control GL as PaymentScenario's
    // transfers — one sub-ledger, many flows — while the expense/tax legs get their own leaf GL
    // accounts (named LSBs, the FeeBillingScenario convention, so journals are trivially
    // attributable in a trace dump).
    private val DEPOSIT_CONTROL_GL: UUID = UUID(0L, 1L)
    private const val INTEREST_EXPENSE_GL_LSB = 6L
    private const val TAX_PAYABLE_GL_LSB = 7L
    private const val SYSTEM_ACTOR_LSB = 8L
    private val INTEREST_EXPENSE_GL: UUID = UUID(0L, INTEREST_EXPENSE_GL_LSB)
    private val TAX_PAYABLE_GL: UUID = UUID(0L, TAX_PAYABLE_GL_LSB)
    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LSB)

    private const val MAX_ACCRUAL_DAYS = 5
    private const val DAILY_RATE_SCALE = 10
    private const val ACCRUAL_SCALE = 6
    private const val DAYS_PER_YEAR = 365
    private val DAY_COUNT_DIVISOR = BigDecimal(DAYS_PER_YEAR)

    /** Realistic deposit rates; which one applies to a step is a seeded choice. */
    private val ANNUAL_RATES = listOf(BigDecimal("0.005"), BigDecimal("0.02"), BigDecimal("0.04"))

    /**
     * The beneficiary mix, drawn per step from the seeded RNG so every `WithholdingTaxPolicy`
     * branch is exercised across a sweep: resident individual (15 %), legal entity (gross, CIT
     * base), treaty non-resident (10 %), non-cooperating non-resident (35 %), and a statutory
     * exemption with evidence on file.
     */
    private val TAX_PROFILES = listOf(
        TaxProfile.FAIL_SAFE_DEFAULT,
        TaxProfile(TaxpayerType.LEGAL_ENTITY, TaxResidency.RESIDENT),
        TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.NON_RESIDENT, treatyRate = BigDecimal("0.10")),
        TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.NON_RESIDENT, nonCooperatingState = true),
        TaxProfile(TaxpayerType.INDIVIDUAL, TaxResidency.RESIDENT, exemptCode = "SIM-EXEMPT"),
    )

    fun step(world: World) {
        val random = world.context.random
        val accountId = random.pick(world.customerAccounts)
        val key = AccountCurrency(accountId, world.currency)
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZONE)

        val config = InterestRateConfig(
            id = random.nextUuid(),
            productId = "sim-deposit",
            currency = world.currency,
            annualRate = random.pick(ANNUAL_RATES),
            effectiveFrom = today.minusYears(1),
            createdAt = now,
            updatedAt = now,
        )

        val accruals = accrue(world, key, config, today, now)
        accruals.forEach { world.interest.recordAccrued(key, it.accruedAmount) }

        // Capitalize the period: the gross is the summed accruals scaled DOWN to currency minor
        // units (never capitalize a haléř that did not accrue — the ≤-accrued conservation law).
        val totalAccrued = accruals.fold(BigDecimal.ZERO) { acc, a -> acc + a.accruedAmount }
        val gross = totalAccrued.setScale(2, RoundingMode.DOWN)
        if (gross.signum() <= 0) return // a zero-balance account accrues nothing worth capitalizing

        val profile = random.pick(TAX_PROFILES)
        val tax = WithholdingTaxPolicy.compute(gross, world.currency, profile, today)
        val capitalization = InterestCapitalization(
            id = random.nextUuid(),
            accountId = accountId,
            productId = config.productId,
            periodFrom = today,
            periodTo = accruals.last().accrualDate,
            totalAccrued = totalAccrued,
            capitalizedAmount = tax.netAmount,
            grossAmount = gross,
            taxAmount = tax.taxAmount,
            netAmount = tax.netAmount,
            currency = world.currency,
            createdAt = now,
        )
        world.interest.recordCapitalized(key, gross, capitalization.netAmount, capitalization.taxAmount)

        val entry = buildCapitalizationJournal(world, capitalization, key, today)
        val idempotencyKey = "interest-cap-${capitalization.id}"

        // Outbox at-least-once: a seeded write fault fails the first dispatch; the redrive is a
        // zero-delay scheduler task the SAME step's drain executes, replaying the identical
        // entry under the identical key (ledger idempotency is the dedup backstop, ADR-0143).
        if (world.context.faults.shouldFailWrite()) {
            world.audit.append("interest cap ${capitalization.id} dispatch failed; outbox redrive scheduled")
            world.context.scheduler.schedule(Duration.ZERO) {
                postAndProject(world, idempotencyKey, entry, capitalization, key)
            }
            return
        }
        postAndProject(world, idempotencyKey, entry, capitalization, key)
    }

    /** Daily accruals over a short seeded period — `InterestService.accrue`'s exact arithmetic. */
    private fun accrue(
        world: World,
        key: AccountCurrency,
        config: InterestRateConfig,
        from: LocalDate,
        now: OffsetDateTime,
    ): List<InterestAccrual> {
        val dailyRate = config.annualRate.divide(DAY_COUNT_DIVISOR, DAILY_RATE_SCALE, RoundingMode.HALF_UP)
        val balance = world.balances.get(key).bookedAmount
        val days = 1 + world.context.random.nextInt(MAX_ACCRUAL_DAYS)
        return (0 until days).map { offset ->
            InterestAccrual(
                id = world.context.random.nextUuid(),
                accountId = key.accountId,
                productId = config.productId,
                configId = config.id,
                accrualDate = from.plusDays(offset.toLong()),
                balance = balance,
                dailyRate = dailyRate,
                accruedAmount = balance.multiply(dailyRate).setScale(ACCRUAL_SCALE, RoundingMode.HALF_UP),
                currency = key.currency,
                createdAt = now,
            )
        }
    }

    /**
     * The ADR-0033 §D capitalization posting: DEBIT interest-expense (gross), CREDIT
     * deposit-control `subAccountId = customer` (net), CREDIT tax-payable (tax, omitted when
     * nothing was withheld). Balanced by the policy's own arithmetic (`net + tax == gross`) —
     * the real `validateBalance()` re-checks it on construction.
     */
    private fun buildCapitalizationJournal(
        world: World,
        capitalization: InterestCapitalization,
        key: AccountCurrency,
        today: LocalDate,
    ): JournalEntry {
        val journalId = world.context.random.nextUuid()
        var sequence = 0
        fun line(side: JournalSide, gl: UUID, amount: BigDecimal, subAccountId: UUID? = null): JournalLine {
            sequence += 1
            val money = Money.of(amount, key.currency)
            return JournalLine(
                id = UUID(journalId.mostSignificantBits, sequence.toLong()),
                journalId = journalId,
                glAccountId = gl,
                side = side,
                amount = money,
                fxRate = null,
                baseAmount = money,
                sequence = sequence,
                subAccountId = subAccountId,
            )
        }

        val lines = buildList {
            add(line(JournalSide.DEBIT, INTEREST_EXPENSE_GL, capitalization.grossAmount))
            add(line(JournalSide.CREDIT, DEPOSIT_CONTROL_GL, capitalization.netAmount, key.accountId))
            if (capitalization.taxAmount.signum() > 0) {
                add(line(JournalSide.CREDIT, TAX_PAYABLE_GL, capitalization.taxAmount))
            }
        }
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = world.context.random.nextUuid(),
            entryDate = today,
            valueDate = today,
            description = "interest capitalization ${capitalization.id}",
            status = JournalStatus.PENDING,
            lines = lines,
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    /** Post idempotently; only the FIRST landing records the book sides and projects balances. */
    private fun postAndProject(
        world: World,
        idempotencyKey: String,
        entry: JournalEntry,
        capitalization: InterestCapitalization,
        key: AccountCurrency,
    ) {
        val posted = world.ledger.post(idempotencyKey, entry)
        if (posted !== entry) return // idempotent replay — already recorded and projected
        world.interest.recordPosted(key, capitalization.netAmount, capitalization.taxAmount)
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
        world.audit.append(
            "interest cap ${capitalization.id} posted: gross=${capitalization.grossAmount} " +
                "net=${capitalization.netAmount} tax=${capitalization.taxAmount}",
        )
    }
}
