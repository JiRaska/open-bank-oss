// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.domain.ProductShadowInput
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Action
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.MaturityAction
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.Predicate
import com.openbank.libs.product.ir.PredicateEvaluator
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvaluationException
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The outcome of holding one legacy fee decision against the product IR. */
sealed interface FeeShadowResult {
    /** The fee was re-decided by the IR rule [ruleId]; [diverged] when charge/waive or amount differ. */
    data class Compared(
        val feeId: String,
        val ruleId: String,
        val diverged: Boolean,
        val legacyCharged: BigDecimal,
        val irCharged: BigDecimal,
    ) : FeeShadowResult

    /** No IR fee (or no scheduled rule charging it) for a legacy fee, or an IR fee legacy never assessed. */
    data class Unmapped(val feeCode: String) : FeeShadowResult

    /** An input required by this fee is unknown; no economic comparison was made. */
    data class UnknownInput(val feeCode: String) : FeeShadowResult
}

/**
 * Re-decides an assessment's fees with the pure product interpreter (ADR-0352 §3, phase 3 shadow).
 *
 * Mapping, stated because a wrong mapping reads as divergence: a legacy fee is matched to the IR fee
 * whose `feeCode` equals its `feeId`, and that fee is charged by the `scheduled` rule whose action is
 * `CHARGE_FEE` of it. The IR is evaluated for that rule's trigger tag over a state built from the
 * frozen assessment inputs, and its `Post` (or the absence of one, which is a waiver) is the IR's
 * decision. A fee present on only one side is [FeeShadowResult.Unmapped], never "agreed".
 *
 * Pure: the interpreter, the inputs and an explicit evaluation instant; no I/O.
 */
class FeeShadowComparator(
    private val interpreter: ProductInterpreter,
    /**
     * The zones a frozen input may name. The recorded zone is a fact about how the legacy decision
     * evaluated, so it is matched against this closed set rather than parsed: an unknown zone makes the
     * input not comparable instead of being re-zoned here (ADR-0207 D1: no zone is declared locally).
     */
    private val admissibleZones: Set<ZoneId> = setOf(ZoneOffset.UTC, AccountingClock.BANK_ZONE),
) {

    fun compare(revision: ProductRevisionIr, input: ProductShadowInput): List<FeeShadowResult> {
        val legacyCodes = input.legacyFees.map { it.feeId }.toSet()
        val irOnly = revision.fees.filter { it.feeCode !in legacyCodes }.map { FeeShadowResult.Unmapped(it.feeCode) }
        val unknownFacts = input.legacyFees.map { FeeShadowResult.UnknownInput(it.feeId) } + irOnly
        // The caller's processing instant is deliberately irrelevant to economic evaluation.
        val capturedAt = input.evaluatedAt
            ?: return unknownFacts
        val zone = admissibleZones.firstOrNull { it.id == input.evaluationZone }
            ?: return unknownFacts
        val clock = ProductClock(capturedAt, zone)
        val state = frozenState(input, zone)
        val trigger = input.scheduledTrigger ?: revision.uniqueScheduledTag(input.scheduledCadence)
        val chargingRules = revision.hooks[Hook.SCHEDULED].orEmpty()
            .filter { it.trigger == trigger && it.action is Action.ChargeFee }
            .groupBy { (it.action as Action.ChargeFee).feeRuleId }
        val results = input.legacyFees.map { legacy ->
            val fee = revision.fees.firstOrNull { it.feeCode == legacy.feeId }
            val candidates = fee?.let { chargingRules[it.ruleId] }.orEmpty()
            val rule = candidates.firstOrNull()
            if (fee == null) {
                FeeShadowResult.Unmapped(legacy.feeId)
            } else if (trigger == null) {
                // The fired cadence names no schedule tag, or more than one: which one fired is unknown.
                FeeShadowResult.UnknownInput(legacy.feeId)
            } else if (rule == null) {
                FeeShadowResult.Unmapped(legacy.feeId)
            } else if (revision.requiresUnknownFacts(rule.trigger, input, state, clock)) {
                FeeShadowResult.UnknownInput(legacy.feeId)
            } else {
                val out = interpreter.evaluate(revision, state, ProductEvent(Hook.SCHEDULED, tag = rule.trigger), clock)
                val chargingIds = candidates.map { it.ruleId }.toSet()
                val posts = out.filterIsInstance<Instruction.Post>().filter { it.ruleId in chargingIds }
                // Money conservation, asserted by the CONSUMER as well as the interpreter (ADR-0352 §8):
                // an unbalanced Post is an interpreter defect and is never compared as if it were a decision.
                if (posts.any { !it.isBalancedPerCurrency() }) {
                    throw ProductEvaluationException("rule ${rule.ruleId} produced an unbalanced Post")
                }
                val irCharged = posts
                    .flatMap { it.lines }
                    .filter {
                        it.accountingClass == AccountingClass.CUSTOMER_DEPOSIT &&
                            it.direction == PostingDirection.DEBIT
                    }
                    .fold(BigDecimal.ZERO) { a, l -> a + l.amount }
                val irWaived = irCharged.signum() == 0
                val diverged = irWaived != legacy.waived ||
                    (!irWaived && irCharged.compareTo(legacy.chargedAmount) != 0)
                FeeShadowResult.Compared(legacy.feeId, fee.ruleId, diverged, legacy.chargedAmount, irCharged)
            }
        }
        return results + irOnly
    }

    private fun frozenState(input: ProductShadowInput, zone: ZoneId) = ProductState(
        currency = input.currency,
        balance = input.balance ?: BigDecimal.ZERO,
        // This placeholder is unobservable: unknownAction below refuses every actual
        // trigger whose actions can read openedOn when the captured fact is absent.
        openedOn = input.openedAt?.atZone(zone)?.toLocalDate() ?: LocalDate.MIN,
        segment = input.segment,
        monthlyTurnover = input.monthlyTurnover,
        aggregatePocketBalance = input.aggregatePocketBalance,
    )

    /** Follows the interpreter's real guard order, including a terminal Reject. */
    private fun ProductRevisionIr.requiresUnknownFacts(
        trigger: String?,
        input: ProductShadowInput,
        state: ProductState,
        clock: ProductClock,
    ): Boolean {
        val facts = FrozenPredicateFacts(input, state, clock.today())
        for (rule in hooks[Hook.SCHEDULED].orEmpty().filter { it.trigger == trigger }) {
            val guard = rule.whenPredicate?.let(facts::truth)
            if (rule.whenPredicate != null && guard == null) return true
            if (guard == false) continue
            if (unknownAction(rule.action, input, facts)) return true
            if (rule.action is Action.Reject) return false
        }
        return false
    }

    private fun ProductRevisionIr.unknownAccrual(input: ProductShadowInput, facts: FrozenPredicateFacts): Boolean {
        val balance = input.balance ?: return true
        if (balance.signum() == 0) return false
        return interest?.bonus?.predicate?.let { facts.truth(it) == null } ?: false
    }

    private fun ProductRevisionIr.unknownAction(
        action: Action,
        input: ProductShadowInput,
        facts: FrozenPredicateFacts,
    ): Boolean = when (action) {
        is Action.ChargeFee -> fees.firstOrNull { it.ruleId == action.feeRuleId }?.waiver
            ?.let { facts.truth(it) == null } ?: false
        Action.AccrueInterest -> unknownAccrual(input, facts)
        // A present interest section reads accruedInterest, not yet supplied by the legacy snapshot.
        Action.CapitaliseInterest -> interest != null
        Action.Mature ->
            interest != null ||
                (
                    termDeposit?.maturityAction == MaturityAction.PAY_OUT &&
                        input.balance == null
                    )
        is Action.Schedule ->
            action.cadence == Cadence.AT_MATURITY &&
                input.openedAt == null
        // A scheduled event carries no posting: this action returns before reading state.
        Action.EarlyWithdrawalPenalty -> false
        else -> false
    }
}

/** Missing facts stay unknown under Not; decisive known And/Or branches need no invented values. */
private class FrozenPredicateFacts(
    private val input: ProductShadowInput,
    private val state: ProductState,
    private val today: LocalDate,
) {
    fun truth(predicate: Predicate): Boolean? = when (predicate) {
        is Predicate.And -> combined(predicate.operands, conjunction = true)
        is Predicate.Or -> combined(predicate.operands, conjunction = false)
        is Predicate.Not -> truth(predicate.operand)?.not()
        else -> leaf(predicate)
    }

    private fun combined(operands: List<Predicate>, conjunction: Boolean): Boolean? {
        var unknown = false
        for (operand in operands) {
            val value = truth(operand)
            if (value == !conjunction) return !conjunction
            if (value == null) unknown = true
        }
        return if (unknown) null else conjunction
    }

    private fun leaf(predicate: Predicate): Boolean? = when (predicate) {
        // No authoritative history snapshot is supplied yet; no empty history is asserted.
        is Predicate.NoEvents, is Predicate.CountEvents, is Predicate.SumEvents, is Predicate.BalanceMin -> null
        is Predicate.Comparison -> if (missing(predicate.attribute)) null else evaluate(predicate)
        else -> evaluate(predicate)
    }

    private fun missing(attribute: WaiveAttribute): Boolean = when (attribute) {
        WaiveAttribute.BALANCE -> input.balance == null
        WaiveAttribute.MONTHLY_TURNOVER -> input.monthlyTurnover == null
        WaiveAttribute.AGGREGATE_POCKET_BALANCE -> input.aggregatePocketBalance == null
        WaiveAttribute.SEGMENT -> input.segment == null
        WaiveAttribute.CURRENCY -> false
    }

    private fun evaluate(predicate: Predicate): Boolean = PredicateEvaluator.test(predicate, state, null, today)
}

/**
 * The schedule tag the legacy cycle fired, derived from the IR's own SCHEDULE actions: a tag is
 * established only when exactly one SCHEDULE into the scheduled hook declares [cadence]. Cadence alone
 * cannot tell two same-cadence tags apart, so an ambiguous or absent match is `null` (not comparable).
 */
internal fun ProductRevisionIr.uniqueScheduledTag(cadence: String?): String? {
    if (cadence == null) return null
    val tags = hooks.values.flatten().map { it.action }.filterIsInstance<Action.Schedule>()
        .filter { it.hook == Hook.SCHEDULED && it.cadence.name == cadence }
        .map { it.tag }.distinct()
    return tags.singleOrNull()
}
