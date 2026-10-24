// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.fx.domain.cnb.CnbFixingRate
import com.openbank.fx.domain.model.FxConversion
import com.openbank.fx.domain.model.FxConversionMath
import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.model.FxRate
import com.openbank.fx.domain.model.RateSource
import com.openbank.fx.domain.model.RateType
import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningPolicy
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.FxSettlementPosting
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Money-path FX adoption (ADR-0011 property-testing family): one step of the customer currency-
 * conversion path, modelled directly against the **real `fx-service` domain** — the `CnbFixingRate`
 * per-unit normalisation, the `FxRate.inverted()` spread-side logic, the `FxConversionMath`
 * cross-scale margin arithmetic and the `ScreeningPolicy` sanctions verdict — not a re-model of
 * any of it. The domain package is framework-free (ADR-0002), so only the domain POJOs land on
 * the classpath.
 *
 * The conversion prices off a ČNB fixing-shaped quote (`X/CZK`, foreign base): a customer buying
 * foreign currency sells CZK, so the scenario takes the **inverted** pair's bid (`1 / ask` — the
 * real `inverted()` swap, never a naive `1 / bid` that would quote the wrong side of the spread);
 * a customer selling foreign currency takes the straight pair's bid. Both legs post real balanced
 * `JournalEntry`s: the CZK leg carries the customer dimension (`subAccountId = accountId`,
 * projected onto the balance store through the bus like [FeeBillingScenario]'s fee legs, with the
 * same affordability guard), the foreign leg is a GL-to-GL house↔position movement the CZK balance
 * projection never sees. Conservation nets per currency; the margin stays in the source currency,
 * exactly as `FxConversionMath.feeMinorUnits` documents.
 *
 * A step:
 *  0. an idempotent replay is a no-op (production's idempotency-key lookup returns the existing
 *     conversion and books nothing);
 *  1. screen the debtor name through the real `ScreeningPolicy` — a BLOCK (or a REVIEW the seeded
 *     reviewer does not clear) fails the conversion before any money moved;
 *  2. an affordability guard on the CZK debit (mirrors FeeBillingScenario's room check) — an
 *     underfunded conversion fails instead of overdrawing;
 *  3. one injected write fault covers BOTH journal posts (they commit together or not at all) —
 *     a fault fails the conversion before any money moved;
 *  4. a post-commit conflict reverses BOTH durable journals through the real
 *     `JournalEntry.reverse()` and the conversion dies REVERSED — money conserved end-to-end;
 *  5. happy path: both journals are durable, the conversion settles, and the invariant sweep
 *     re-derives `toAmount` and the fee from the stored rate through the real `FxConversionMath`.
 *
 * Every conversion reaches a terminal status within the step, which is what
 * `MoneyPathInvariants.fxCompleteness` asserts after the scheduler drains.
 */
object FxConversionScenario {

    private const val IDEMPOTENT_REPLAY_RATE = 0.05
    private const val BUY_FOREIGN_RATE = 0.5
    private const val SCREEN_BLOCK_RATE = 0.06
    private const val SCREEN_REVIEW_RATE = 0.10
    private const val REVIEW_CLEAR_RATE = 0.5
    private const val BLOCK_SCORE = 1.0
    private const val REVIEW_SCORE = 0.5
    private const val CLEAR_SCORE = 0.0
    private const val FROM_CZK_MIN_MINOR = 20_000L // 200.00 CZK — a retail conversion, not a payment
    private const val FROM_CZK_MAX_MINOR = 150_000L // 1 500.00 CZK
    private const val FROM_FOREIGN_MIN_MINOR = 800L // 8.00 in a 2-dp currency
    private const val FROM_FOREIGN_MAX_MINOR = 6_000L // 60.00
    private const val LINE_THREE_SEQUENCE = 3L // detekt's MagicNumber ignores only -1,0,1,2
    private const val LINE_FOUR_SEQUENCE = 4L

    // A dedicated FX chart fragment: CZK house + fee GLs (customer dimension via subAccountId),
    // one shared foreign house/position pair (the foreign leg is GL-to-GL — the world opens no
    // foreign-currency customer balances). Named least-significant-bits, same convention as the
    // other scenarios' GL constants.
    private const val FX_CZK_HOUSE_GL_LSB = 31L
    private const val FX_FEE_GL_LSB = 32L
    private const val FX_FGN_HOUSE_GL_LSB = 33L
    private const val FX_FGN_POS_GL_LSB = 34L
    private const val SYSTEM_ACTOR_LSB = 35L
    private val FX_CZK_HOUSE_GL: UUID = UUID(0L, FX_CZK_HOUSE_GL_LSB)
    private val FX_FEE_GL: UUID = UUID(0L, FX_FEE_GL_LSB)
    private val FX_FGN_HOUSE_GL: UUID = UUID(0L, FX_FGN_HOUSE_GL_LSB)
    private val FX_FGN_POS_GL: UUID = UUID(0L, FX_FGN_POS_GL_LSB)
    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LSB)

    fun step(world: World) {
        // 0. An idempotent replay returns the persisted conversion and posts nothing.
        if (world.fx.conversions.isNotEmpty() && world.context.random.chance(IDEMPOTENT_REPLAY_RATE)) {
            world.audit.append("fx idempotent replay: no-op")
            return
        }
        val leg = openLeg(world)

        // 1+2. The real sanctions verdict and the affordability guard both fail the conversion
        //    before any money moved.
        if (!admit(world, leg)) return

        // 3. One write fault covers BOTH posts: they commit together or not at all.
        if (post(world, leg)) return

        // 4. A post-commit conflict unwinds both durable journals and the conversion reverses.
        if (conflict(world, leg)) return

        // 5. Happy path: both journals are durable and the conversion settles.
        leg.advance(world, FxConversionStatus.SETTLED, settled = true)
    }

    private fun openLeg(world: World): FxLeg {
        val random = world.context.random
        val accountId = random.pick(world.customerAccounts)
        val rate = quoteRate(world)
        val code = rate.baseCurrency

        val buyingForeign = random.chance(BUY_FOREIGN_RATE)
        // Customer sells CZK → the real inverted pair's BID (1/ask, the correct spread side);
        // customer sells foreign → the bank buys the base at its straight bid.
        val plan = if (buyingForeign) {
            FxPlan(
                world.currency,
                code,
                random.nextLong(FROM_CZK_MIN_MINOR, FROM_CZK_MAX_MINOR),
                rate.inverted().bidRate,
            )
        } else {
            FxPlan(
                code,
                world.currency,
                random.nextLong(FROM_FOREIGN_MIN_MINOR, FROM_FOREIGN_MAX_MINOR),
                rate.bidRate,
            )
        }
        val conversion = FxConversion(
            id = random.nextUuid(),
            idempotencyKey = "fx-${random.nextUuid()}",
            partyId = accountId,
            accountId = accountId,
            fromCurrency = plan.fromCurrency,
            toCurrency = plan.toCurrency,
            fromAmountMinorUnits = plan.fromMinor,
            toAmountMinorUnits = FxConversionMath.convertedAmountMinorUnits(
                plan.fromMinor,
                plan.fromCurrency,
                plan.toCurrency,
                plan.appliedRate,
            ),
            appliedRate = plan.appliedRate,
            feeMinorUnits = FxConversionMath.feeMinorUnits(plan.fromMinor),
            rateId = rate.id,
            status = FxConversionStatus.PENDING,
            createdAt = world.context.clock.instant(),
            settledAt = null,
        )
        world.fx.recordConversion(conversion)
        return FxLeg(conversion, world.fx.conversions.lastIndex, buyingForeign)
    }

    /** The priced direction of one conversion attempt. */
    private data class FxPlan(
        val fromCurrency: String,
        val toCurrency: String,
        val fromMinor: Long,
        val appliedRate: BigDecimal,
    )

    /**
     * The real screening verdict plus the affordability guard; returns false when the conversion
     * failed before any money moved.
     */
    private fun admit(world: World, leg: FxLeg): Boolean {
        val random = world.context.random
        val status = when {
            random.chance(SCREEN_BLOCK_RATE) -> ScreeningMatchStatus.HIT to BLOCK_SCORE
            random.chance(SCREEN_REVIEW_RATE) -> ScreeningMatchStatus.POTENTIAL_HIT to REVIEW_SCORE
            else -> ScreeningMatchStatus.CLEAR to CLEAR_SCORE
        }
        val results = listOf(
            ScreeningResult(
                subject = "Simulated Debtor",
                role = ScreeningRole.DEBTOR,
                status = status.first,
                score = status.second,
                matchedEntity = null,
            ),
        )
        val cleared = when (ScreeningPolicy.decide(results)) {
            ScreeningDecision.BLOCK -> false
            // A REVIEW the seeded reviewer does not clear fails too; a cleared one proceeds.
            ScreeningDecision.REVIEW -> random.chance(REVIEW_CLEAR_RATE)
            ScreeningDecision.CLEAR -> true
        }
        if (!cleared) {
            leg.advance(world, FxConversionStatus.FAILED)
            return false
        }
        if (leg.buyingForeign) {
            // A CZK debit the account cannot cover fails instead of overdrawing.
            val key = AccountCurrency(leg.conversion.accountId!!, world.currency)
            val availableMinor = world.balances.get(key).available().movePointRight(2).longValueExact()
            val needed = leg.conversion.fromAmountMinorUnits + leg.conversion.feeMinorUnits
            if (availableMinor < needed) {
                leg.advance(world, FxConversionStatus.FAILED)
                return false
            }
        }
        return true
    }

    /** Posts BOTH journals under one fault draw (they commit together or not at all). */
    private fun post(world: World, leg: FxLeg): Boolean = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("fx settlement post failed for ${leg.conversion.id}")
        }
        val czk = buildCzkEntry(world, leg)
        val foreign = buildForeignEntry(world, leg)
        world.ledger.post("fx-${leg.conversion.id}-czk", czk)
        world.ledger.post("fx-${leg.conversion.id}-foreign", foreign)
        publishBooked(world, czk)
        publishBooked(world, foreign)
        world.fx.recordPosting(
            FxSettlementPosting(
                conversionId = leg.conversion.id,
                czkJournalId = czk.id,
                foreignJournalId = foreign.id,
            ),
        )
        false
    } catch (e: SimulatedWriteFailure) {
        leg.advance(world, FxConversionStatus.FAILED)
        world.audit.append("fx ${leg.conversion.id} failed: ${e.message}")
        true
    }

    /** Post-commit conflict: the real reversals unwind both journals and the conversion reverses. */
    private fun conflict(world: World, leg: FxLeg): Boolean {
        if (!world.context.faults.shouldConflict()) return false
        val posting = world.fx.postingOf(leg.conversion.id)
        check(posting != null) { "conflicted fx conversion ${leg.conversion.id} has no settlement posting" }
        listOfNotNull(posting.czkJournalId, posting.foreignJournalId).forEach { journalId ->
            val reversal = world.ledger.reverse(
                journalId,
                world.context.random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { world.context.random.nextUuid() },
            )
            publishBooked(world, reversal)
        }
        world.fx.replacePosting(posting.copy(czkReversed = true, foreignReversed = true))
        leg.advance(world, FxConversionStatus.REVERSED)
        return true
    }

    /** CZK leg: the customer dimension is carried on the subAccountId lines and projected. */
    private fun buildCzkEntry(world: World, leg: FxLeg): JournalEntry {
        val conversion = leg.conversion
        val journalId = world.context.random.nextUuid()
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        fun line(sequence: Long, gl: UUID, side: JournalSide, minor: Long, sub: UUID?) =
            journalLine(journalId, sequence, gl, side, minor, world.currency, sub)
        val fromMinor = conversion.fromAmountMinorUnits
        val toMinor = conversion.toAmountMinorUnits
        val fee = conversion.feeMinorUnits
        val account = conversion.accountId
        val lines = if (leg.buyingForeign) {
            // Customer debited principal + margin; house keeps the principal, fee-income the fee.
            listOf(
                line(1L, FX_CZK_HOUSE_GL, JournalSide.DEBIT, fromMinor, account),
                line(2L, FX_CZK_HOUSE_GL, JournalSide.CREDIT, fromMinor, null),
                line(LINE_THREE_SEQUENCE, FX_CZK_HOUSE_GL, JournalSide.DEBIT, fee, account),
                line(LINE_FOUR_SEQUENCE, FX_FEE_GL, JournalSide.CREDIT, fee, null),
            )
        } else {
            // Customer credited the converted CZK; the house funds it.
            listOf(
                line(1L, FX_CZK_HOUSE_GL, JournalSide.DEBIT, toMinor, null),
                line(2L, FX_CZK_HOUSE_GL, JournalSide.CREDIT, toMinor, account),
            )
        }
        return journal(journalId, conversion, today, world, lines)
    }

    /** Foreign leg: GL-to-GL house↔position movement; the margin stays in the source currency. */
    private fun buildForeignEntry(world: World, leg: FxLeg): JournalEntry {
        val conversion = leg.conversion
        val journalId = world.context.random.nextUuid()
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        fun line(sequence: Long, gl: UUID, side: JournalSide, minor: Long, currency: String, sub: UUID?) =
            journalLine(journalId, sequence, gl, side, minor, currency, sub)
        val fromMinor = conversion.fromAmountMinorUnits
        val fee = conversion.feeMinorUnits
        val fromCcy = conversion.fromCurrency
        val lines = if (leg.buyingForeign) {
            val toMinor = conversion.toAmountMinorUnits
            val toCcy = conversion.toCurrency
            listOf(
                line(1L, FX_FGN_HOUSE_GL, JournalSide.DEBIT, toMinor, toCcy, null),
                line(2L, FX_FGN_POS_GL, JournalSide.CREDIT, toMinor, toCcy, null),
            )
        } else {
            val principal = fromMinor - fee
            listOf(
                line(1L, FX_FGN_POS_GL, JournalSide.DEBIT, fromMinor, fromCcy, null),
                line(2L, FX_FGN_HOUSE_GL, JournalSide.CREDIT, principal, fromCcy, null),
                line(LINE_THREE_SEQUENCE, FX_FEE_GL, JournalSide.CREDIT, fee, fromCcy, null),
            )
        }
        return journal(journalId, conversion, today, world, lines)
    }

    private fun journal(
        journalId: UUID,
        conversion: FxConversion,
        today: LocalDate,
        world: World,
        lines: List<JournalLine>,
    ): JournalEntry = JournalEntry(
        id = journalId,
        entryNumber = null,
        transactionId = conversion.id,
        entryDate = today,
        valueDate = today,
        description = "fx conversion ${conversion.id}",
        status = JournalStatus.PENDING,
        lines = lines,
        createdAt = world.context.clock.instant(),
        createdBy = SYSTEM_ACTOR,
        version = 0L,
    ).post()

    private fun journalLine(
        journalId: UUID,
        sequence: Long,
        glAccountId: UUID,
        side: JournalSide,
        amountMinorUnits: Long,
        currency: String,
        subAccountId: UUID?,
    ): JournalLine {
        val money = Money.of(
            BigDecimal.valueOf(amountMinorUnits, CurrencyCode.of(currency).defaultFractionDigits),
            currency,
        )
        return JournalLine(
            id = UUID(journalId.mostSignificantBits, sequence),
            journalId = journalId,
            glAccountId = glAccountId,
            side = side,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = sequence.toInt(),
            subAccountId = subAccountId,
        )
    }

    /** Project the entry's real `bookedDeltas()` onto the bus — same as [FeeBillingScenario]. */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }

    /** The real domain aggregate driven by one step, with its index into [World]. */
    private data class FxLeg(val conversion: FxConversion, val index: Int, val buyingForeign: Boolean) {
        fun advance(world: World, to: FxConversionStatus, settled: Boolean = false): FxLeg {
            val now = world.context.clock.instant()
            val next = conversion.copy(
                status = to,
                settledAt = if (settled) now else conversion.settledAt,
                createdAt = conversion.createdAt,
            )
            world.fx.replaceConversion(next)
            world.audit.append("fx ${next.id} -> ${next.status}")
            return copy(conversion = next)
        }
    }
}

private const val SPREAD_BASIS_POINTS = "0.002" // ±0.2% half-spread around the fixing
private const val JITTER_MIN = 99L
private const val JITTER_MAX = 101L
private const val JITTER_DIVISOR = 100L

// ČNB-fixing-shaped quote bases (CZK per [amount] units of [code]); the step jitters the rate
// ±1% so consecutive steps do not quote an identical market.
private val FIXING_BASES = listOf(
    Triple("EUR", 1, BigDecimal("25.20")),
    Triple("USD", 1, BigDecimal("23.10")),
    Triple("GBP", 1, BigDecimal("29.80")),
)

/** A fresh fixing-shaped `X/CZK` spot quote priced off the real per-unit normalisation. */
private fun quoteRate(world: World): FxRate {
    val random = world.context.random
    val (code, amount, baseRate) = random.pick(FIXING_BASES)
    val jitter = BigDecimal(random.nextLong(JITTER_MIN, JITTER_MAX))
        .divide(BigDecimal.valueOf(JITTER_DIVISOR))
    // The real per-unit normalisation of a per-N quote (JPY is quoted per 100).
    val perUnit = CnbFixingRate(code, amount, baseRate.multiply(jitter)).ratePerUnit
    val spread = BigDecimal(SPREAD_BASIS_POINTS)
    val now = world.context.clock.instant()
    return FxRate(
        id = random.nextUuid(),
        baseCurrency = code,
        quoteCurrency = world.currency,
        bidRate = perUnit.multiply(BigDecimal.ONE.subtract(spread)),
        askRate = perUnit.multiply(BigDecimal.ONE.add(spread)),
        rateType = RateType.SPOT,
        source = RateSource.CNB,
        validFrom = now,
        validTo = now.plus(Duration.ofDays(1)),
        createdAt = now,
    )
}
