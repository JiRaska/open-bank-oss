// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import java.math.BigDecimal

/**
 * Product IR v1 (ADR-0352 §1–§2): the typed, total, closed representation of a deposit product's
 * behaviour. Every construct here has a one-to-one form in `product-ir/v1.schema.json`; a document
 * the schema rejects has no model, and a model has no field the schema does not name.
 *
 * Nothing in this file computes anything. It is data; [ProductInterpreter] executes it.
 */
object IrVersion {
    const val V1: Int = 1

    /** Every `irVersion` the interpreter accepts. Removal of a version is a breaking change (ADR-0352). */
    val SUPPORTED: Set<Int> = setOf(V1)
}

/**
 * Hard run limits (ADR-0352 §2). They are part of the schema — the JSON Schema states the same
 * numbers in `maxItems` / `maximum` — and the parser checks them again, so a document that reaches
 * the interpreter cannot exceed them. [ProductInterpreter] enforces [MAX_INSTRUCTIONS_PER_EVALUATION]
 * a third time at runtime, because a limit the executor does not hold is a convention.
 */
object IrLimits {
    const val MAX_INSTRUCTIONS_PER_EVALUATION: Int = 64
    const val MAX_TIERS: Int = 16
    const val MAX_FEES: Int = 32
    const val MAX_PREDICATE_DEPTH: Int = 8

    /** Operands of one `and` / `or` node — a breadth limit, independent of [MAX_PREDICATE_DEPTH]. */
    const val MAX_AND_OR_OPERANDS: Int = 8
    const val MAX_RULES_PER_HOOK: Int = 32
    const val MAX_NOTIFY_VARIABLES: Int = 16
    const val MAX_RULE_ID_LENGTH: Int = 64
    const val MAX_TERM_MONTHS: Int = 600
    const val MAX_WINDOW_DAYS: Int = 3660

    /** Decimal places a rate or fraction may carry; also its canonical scale (ADR-0352 §4). */
    const val RATE_SCALE: Int = 10

    /** Decimal places an amount or threshold may carry; also its canonical scale (ADR-0352 §4). */
    const val AMOUNT_SCALE: Int = 4

    /**
     * Canonical scale of an amount on an emitted instruction. Wider than [AMOUNT_SCALE] because an
     * accrual is posted at its policy's scale (6 for INTEREST_ACCRUAL, up to 12 in the registry), and
     * a canonical form that rounded it would make two different instructions one.
     */
    const val INSTRUCTION_AMOUNT_SCALE: Int = 12

    /** `ruleId`: lower-case dotted identifiers, never an index (ADR-0352 §2). */
    val RULE_ID_PATTERN: Regex = Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)*$")
}

enum class RateType { FIXED, TIERED }

/**
 * The v1 day-count vocabulary (ADR-0352 §1), mapped one-to-one onto the kernel [DayCount]. The IR
 * spells `ACT_ACT` where the kernel spells `ACT_ACT_ISDA`; the kernel enum is not widened.
 */
enum class IrDayCount(val kernel: DayCount) {
    ACT_365_FIXED(DayCount.ACT_365_FIXED),
    ACT_360(DayCount.ACT_360),
    ACT_ACT(DayCount.ACT_ACT_ISDA),
    THIRTY_360_BOND_BASIS(DayCount.THIRTY_360_BOND_BASIS),
}

enum class Cadence { DAILY, MONTHLY, QUARTERLY, ANNUALLY, AT_MATURITY, ON_EVENT }

enum class Hook { ON_OPEN, PRE_POSTING, POST_POSTING, SCHEDULED, ON_PARAMETER_CHANGE, ON_CLOSE }

enum class MaturityAction { CAPITALISE, PAY_OUT, ROLL_OVER }

enum class PenaltyKind { RATE, FORFEITED_INTEREST_FRACTION }

/** Kinds of account event the predicate language can count, sum or require absent. */
enum class EventKind { DEPOSIT, WITHDRAWAL, CARD_PAYMENT, TRANSFER_OUT, TRANSFER_IN, FEE, INTEREST, PENALTY }

/** A rolling window ending at the evaluation date (inclusive of today, `days` days back). */
data class Window(val days: Int)

/**
 * The closed predicate language (ADR-0352 §1): the ADR-0138 comparison grammar migrated unchanged
 * as [Comparison], the event-history predicates that close ADR-0138 phase 3, a posting-time
 * comparison for `prePosting` rules, and `and`/`or`/`not`.
 */
sealed interface Predicate {
    /** `WaivePredicate.Comparison`, same attributes, same operators, same semantics. */
    data class Comparison(
        val attribute: WaiveAttribute,
        val operator: WaiveOperator,
        val threshold: BigDecimal? = null,
        val textValue: String? = null,
        val currency: String? = null,
    ) : Predicate

    /** Attributes of the posting being evaluated — meaningful in `prePosting` / `postPosting` only. */
    data class PostingComparison(
        val attribute: PostingAttribute,
        val operator: WaiveOperator,
        val threshold: BigDecimal,
    ) : Predicate

    data class NoEvents(val kind: EventKind, val window: Window) : Predicate

    data class CountEvents(val kind: EventKind, val window: Window, val operator: WaiveOperator, val count: Int) :
        Predicate

    data class SumEvents(val kind: EventKind, val window: Window, val operator: WaiveOperator, val amount: BigDecimal) :
        Predicate

    data class BalanceMin(val window: Window, val operator: WaiveOperator, val amount: BigDecimal) : Predicate

    data class And(val operands: List<Predicate>) : Predicate

    data class Or(val operands: List<Predicate>) : Predicate

    data class Not(val operand: Predicate) : Predicate
}

enum class PostingAttribute { POSTING_AMOUNT, PROJECTED_BALANCE }

data class Tier(val from: BigDecimal, val to: BigDecimal?, val rate: BigDecimal)

data class BonusRate(val ruleId: String, val rate: BigDecimal, val predicate: Predicate)

/**
 * Interest terms. [accrualRounding] and [postingRounding] name kernel [RoundingPolicy] values so
 * the IR carries the rounding rule by name, never as a loose (scale, mode) pair (ADR-0318).
 */
data class InterestTerms(
    val ruleId: String,
    val rateType: RateType,
    val rate: BigDecimal?,
    val tiers: List<Tier>,
    val dayCount: IrDayCount,
    val accrualCadence: Cadence,
    val capitalisationCadence: Cadence,
    val accrualRounding: RoundingPolicy,
    val postingRounding: RoundingPolicy,
    val bonus: BonusRate?,
)

/** Ledger accounting classes a [Instruction.Post] line may carry. Closed; the consumer maps them to GL accounts. */
enum class AccountingClass {
    CUSTOMER_DEPOSIT,
    ACCRUED_INTEREST_PAYABLE,
    INTEREST_EXPENSE,
    FEE_INCOME,
    PENALTY_INCOME,
    SETTLEMENT,
}

data class FeeTerms(
    val ruleId: String,
    val feeCode: String,
    val amount: BigDecimal,
    val cadence: Cadence,
    val accountingClass: AccountingClass,
    val waiver: Predicate?,
)

data class EarlyWithdrawalPenalty(
    val ruleId: String,
    val kind: PenaltyKind,
    val rate: BigDecimal?,
    val fraction: BigDecimal?,
)

data class TermDepositTerms(
    val ruleId: String,
    val termMonths: Int,
    val maturityAction: MaturityAction,
    val earlyWithdrawal: EarlyWithdrawalPenalty?,
)

/**
 * The closed action set a hook rule may perform. Every action maps to exactly one instruction
 * family; the section it reads (interest, a fee, the term) is resolved at validation time.
 */
sealed interface Action {
    data class Schedule(val hook: Hook, val tag: String, val cadence: Cadence) : Action

    data object AccrueInterest : Action

    data object CapitaliseInterest : Action

    data class ChargeFee(val feeRuleId: String) : Action

    data class Reject(val reasonCode: String) : Action

    data class Notify(val templateId: String, val variables: Map<String, String>) : Action

    data class Reprice(val parameter: String, val value: BigDecimal) : Action

    data object Mature : Action

    data object EarlyWithdrawalPenalty : Action
}

/**
 * One rule on one hook. [trigger] names the schedule tag that fires it (`scheduled` hook only);
 * [`when`][whenPredicate] gates it; [action] is what it does.
 */
data class HookRule(val ruleId: String, val trigger: String?, val whenPredicate: Predicate?, val action: Action)

/** A published revision's whole behaviour. [revisionHash] is computed by [ProductIrCanonical], never authored. */
data class ProductRevisionIr(
    val irVersion: Int,
    val productCode: String,
    val currency: String,
    val interest: InterestTerms?,
    val fees: List<FeeTerms>,
    val termDeposit: TermDepositTerms?,
    val hooks: Map<Hook, List<HookRule>>,
    val revisionHash: String,
) {
    val allRuleIds: List<String>
        get() = buildList {
            interest?.let {
                add(it.ruleId)
                it.bonus?.let { b -> add(b.ruleId) }
            }
            fees.forEach { add(it.ruleId) }
            termDeposit?.let {
                add(it.ruleId)
                it.earlyWithdrawal?.let { e -> add(e.ruleId) }
            }
            hooks.values.flatten().forEach { add(it.ruleId) }
        }
}
