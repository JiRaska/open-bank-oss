// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.domain.ProductShadowInput
import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Action
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.Predicate
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvaluationException
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import java.math.BigDecimal
import java.time.Instant
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
class FeeShadowComparator(private val interpreter: ProductInterpreter) {

    fun compare(revision: ProductRevisionIr, input: ProductShadowInput, at: Instant): List<FeeShadowResult> {
        val clock = ProductClock(at, ZoneOffset.UTC)
        val state = ProductState(
            currency = input.currency,
            balance = input.balance ?: BigDecimal.ZERO,
            openedOn = clock.today(),
            segment = input.segment,
            monthlyTurnover = input.monthlyTurnover,
            aggregatePocketBalance = input.aggregatePocketBalance,
        )
        val chargingRules = revision.hooks[Hook.SCHEDULED].orEmpty()
            .mapNotNull { rule -> (rule.action as? Action.ChargeFee)?.let { it.feeRuleId to rule } }
            .toMap()
        val legacyCodes = input.legacyFees.map { it.feeId }.toSet()
        val results = input.legacyFees.map { legacy ->
            val fee = revision.fees.firstOrNull { it.feeCode == legacy.feeId }
            val rule = fee?.let { chargingRules[it.ruleId] }
            if (fee == null || rule?.trigger == null) {
                FeeShadowResult.Unmapped(legacy.feeId)
            } else if (input.balance == null &&
                revision.requiresBalance(rule.trigger)
            ) {
                FeeShadowResult.UnknownInput(legacy.feeId)
            } else {
                val out = interpreter.evaluate(revision, state, ProductEvent(Hook.SCHEDULED, tag = rule.trigger), clock)
                val posts = out.filterIsInstance<Instruction.Post>().filter { it.ruleId == rule.ruleId }
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
        val irOnly = revision.fees.filter { it.feeCode !in legacyCodes }.map { FeeShadowResult.Unmapped(it.feeCode) }
        return results + irOnly
    }

    // evaluate() executes every rule for this trigger, including their instruction-limit effects.
    // Do not invent a balance for any of them, but leave unrelated triggers comparable.
    private fun ProductRevisionIr.requiresBalance(trigger: String?): Boolean =
        hooks[Hook.SCHEDULED].orEmpty().filter { it.trigger == trigger }.any { rule ->
            rule.whenPredicate.requiresBalance() ||
                when (val action = rule.action) {
                    is Action.ChargeFee -> fees.firstOrNull { it.ruleId == action.feeRuleId }?.waiver.requiresBalance()
                    Action.AccrueInterest, Action.Mature, Action.EarlyWithdrawalPenalty -> true
                    else -> false
                }
        }

    private fun Predicate?.requiresBalance(): Boolean = when (this) {
        is Predicate.Comparison -> attribute == WaiveAttribute.BALANCE
        is Predicate.And -> operands.any { it.requiresBalance() }
        is Predicate.Or -> operands.any { it.requiresBalance() }
        is Predicate.Not -> operand.requiresBalance()
        else -> false
    }
}
