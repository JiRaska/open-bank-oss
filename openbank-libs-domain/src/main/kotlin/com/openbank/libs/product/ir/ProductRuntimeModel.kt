// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The only clock the interpreter may read (ADR-0100, ADR-0352 §3). Supplied by the caller per evaluation. */
data class ProductClock(val now: Instant, val zone: ZoneId) {
    fun today(): LocalDate = now.atZone(zone).toLocalDate()
}

/** One historical account event the predicate language can inspect. */
data class AccountEvent(val kind: EventKind, val at: LocalDate, val amount: BigDecimal)

/** A balance observation; `balanceMin(window)` is the minimum over the samples inside the window. */
data class BalanceSample(val on: LocalDate, val balance: BigDecimal)

/**
 * Everything the interpreter may know about the account (ADR-0352 §3: state in, instructions out).
 * It is a snapshot the consumer assembles; the interpreter never reads anything else.
 */
data class ProductState(
    val currency: String,
    val balance: BigDecimal,
    val accruedInterest: BigDecimal = BigDecimal.ZERO,
    val openedOn: LocalDate,
    val segment: String? = null,
    val monthlyTurnover: BigDecimal? = null,
    val aggregatePocketBalance: BigDecimal? = null,
    val events: List<AccountEvent> = emptyList(),
    val balanceSamples: List<BalanceSample> = emptyList(),
    /** Parameter overrides applied by earlier `Reprice` instructions (`interest.rate` …). */
    val parameters: Map<String, BigDecimal> = emptyMap(),
)

enum class PostingDirection { DEBIT, CREDIT }

/** The posting a `prePosting` / `postPosting` hook is asked about: a customer movement, signed by [direction]. */
data class Posting(val kind: EventKind, val direction: PostingDirection, val amount: BigDecimal)

/** What triggered this evaluation. Exactly the fields the [hook] needs; the rest are null. */
data class ProductEvent(
    val hook: Hook,
    /** `scheduled` only: the schedule tag that fired. */
    val tag: String? = null,
    /** `prePosting` / `postPosting` only. */
    val posting: Posting? = null,
    /** `onParameterChange` only. */
    val parameter: String? = null,
    val newValue: BigDecimal? = null,
)

/** One side of a balanced posting. [amount] is positive; [direction] says which side. */
data class PostingLine(
    val accountingClass: AccountingClass,
    val direction: PostingDirection,
    val amount: BigDecimal,
    val currency: String,
)

/**
 * The closed instruction set (ADR-0352 §2). Every instruction names the rule that produced it, the
 * revision it was produced under and the IR version the revision was written in.
 */
sealed interface Instruction {
    val ruleId: String
    val revisionHash: String
    val irVersion: Int

    data class Post(
        override val ruleId: String,
        override val revisionHash: String,
        override val irVersion: Int,
        val lines: List<PostingLine>,
        val narrative: String,
    ) : Instruction {
        /** Money conservation: per currency, debits equal credits. Checked by the interpreter before it returns. */
        fun isBalancedPerCurrency(): Boolean = lines.groupBy { it.currency }.values.all { ls ->
            val debit = ls.filter { it.direction == PostingDirection.DEBIT }.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
            val credit = ls.filter { it.direction == PostingDirection.CREDIT }.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
            debit.compareTo(credit) == 0
        }
    }

    data class Reject(
        override val ruleId: String,
        override val revisionHash: String,
        override val irVersion: Int,
        val reasonCode: String,
    ) : Instruction

    data class Schedule(
        override val ruleId: String,
        override val revisionHash: String,
        override val irVersion: Int,
        val hook: Hook,
        val tag: String,
        val cadence: Cadence,
        /** First firing date for a date-bound cadence (`AT_MATURITY`); null for a pure cadence. */
        val at: LocalDate?,
    ) : Instruction

    data class Notify(
        override val ruleId: String,
        override val revisionHash: String,
        override val irVersion: Int,
        val templateId: String,
        val variables: Map<String, String>,
    ) : Instruction

    data class Reprice(
        override val ruleId: String,
        override val revisionHash: String,
        override val irVersion: Int,
        val parameter: String,
        val value: BigDecimal,
    ) : Instruction
}

/**
 * The interest and penalty arithmetic the interpreter delegates to (ADR-0352 §5: the primitives
 * live in `openbank-libs-finance`, which depends on this module, so the interpreter sees them
 * through this port and computes nothing itself). `openbank-libs-finance` provides the one
 * implementation; a test may provide another.
 */
interface ProductArithmetic {
    /** One day's accrual on [balance] at [annualRate] for the period `[date, date + 1)`. */
    fun dailyAccrual(
        balance: BigDecimal,
        annualRate: BigDecimal,
        dayCount: IrDayCount,
        date: LocalDate,
        accrualRounding: RoundingPolicy,
    ): BigDecimal

    /** One day's marginal tiered accrual: each band of [balance] earns its own tier rate. */
    fun tieredDailyAccrual(
        balance: BigDecimal,
        tiers: List<Tier>,
        dayCount: IrDayCount,
        date: LocalDate,
        accrualRounding: RoundingPolicy,
    ): BigDecimal

    /** The amount a capitalisation posts from [accrued], at the currency's posting scale. */
    fun capitalise(accrued: BigDecimal, currency: CurrencyCode, postingRounding: RoundingPolicy): BigDecimal

    /** Early-withdrawal penalty as a rate of the withdrawn [amount]. */
    fun penaltyByRate(amount: BigDecimal, rate: BigDecimal, currency: CurrencyCode, postingRounding: RoundingPolicy): BigDecimal

    /** Early-withdrawal penalty as a forfeited [fraction] of the [accrued] interest. */
    fun forfeitedInterest(accrued: BigDecimal, fraction: BigDecimal, currency: CurrencyCode, postingRounding: RoundingPolicy): BigDecimal
}

/** Thrown when an evaluation would exceed [IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION] or emit an unbalanced `Post`. */
class ProductEvaluationException(message: String) : IllegalStateException(message)
