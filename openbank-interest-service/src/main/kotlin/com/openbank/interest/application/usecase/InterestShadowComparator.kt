// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.domain.shadow.ShadowLegacyAccrual
import com.openbank.interest.domain.shadow.ShadowLegacyCapitalization
import com.openbank.interest.domain.shadow.ShadowLegacyDecision
import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Action
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.HookRule
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductEvaluationException
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.ir.RateType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/** The outcome of holding one legacy interest decision against the product IR. */
sealed interface InterestShadowResult {
    /** Re-decided by the IR rule [ruleId]; [diverged] when the amounts differ at all (no tolerance). */
    data class Compared(
        val ruleId: String,
        val diverged: Boolean,
        val legacyAmount: BigDecimal,
        val irAmount: BigDecimal,
    ) : InterestShadowResult

    /** The IR has no counterpart for the legacy decision, or a construct the legacy path never applies. */
    data class Unmapped(val what: String) : InterestShadowResult
}

/**
 * Re-decides one interest-service decision with the pure product interpreter (ADR-0352 §3, §10,
 * phase 3 shadow). Pure: the interpreter, the frozen legacy row and nothing else; no I/O, no clock.
 *
 * Mapping, stated because a wrong mapping reads as divergence:
 * - an **accrual** is the IR's `scheduled` rule whose action is `ACCRUE_INTEREST`, evaluated on the
 *   accrual's own balance with the clock set to its accrual date; the IR amount is the debit of
 *   interest expense on the `Post` whose `ruleId` is the interest terms' `ruleId`, and it is held to
 *   `interest_accruals.accrued_amount` (scale 6) exactly;
 * - a **capitalisation** is the `scheduled` rule whose action is `CAPITALISE_INTEREST`, evaluated with
 *   `accruedInterest` = the claimed set's raw sum; the IR amount is the credit to the customer deposit,
 *   held to `interest_capitalizations.gross_amount` (currency scale) exactly. Withholding tax is not
 *   compared: IR v1 cannot express it.
 *
 * Things the legacy path never does are [InterestShadowResult.Unmapped], never silently compared: a
 * loyalty bonus, and a TIERED rate with more than one band (legacy applies one rate to the whole
 * balance; the IR's tiers are marginal). An accrual whose rate was an operator override rather than
 * the pinned revision is not the revision's decision and is unmapped too.
 *
 * Money conservation (ADR-0352 §8) is asserted here as well as in the interpreter: an unbalanced
 * `Post` is an interpreter defect and is thrown, never compared.
 */
class InterestShadowComparator(private val interpreter: ProductInterpreter) {

    fun compare(revision: ProductRevisionIr, legacy: ShadowLegacyDecision): List<InterestShadowResult> {
        val terms = revision.interest ?: return listOf(InterestShadowResult.Unmapped("interest"))
        if (!revision.currency.equals(legacy.currency, ignoreCase = true)) {
            return listOf(InterestShadowResult.Unmapped("currency"))
        }
        return when (legacy) {
            is ShadowLegacyAccrual -> accrual(revision, terms.ruleId, legacy) + extras(revision)
            is ShadowLegacyCapitalization -> listOf(capitalisation(revision, legacy))
        }
    }

    private fun accrual(revision: ProductRevisionIr, termsRuleId: String, legacy: ShadowLegacyAccrual) =
        when {
            !legacy.decidedByPin -> listOf(InterestShadowResult.Unmapped("override-rate"))
            revision.interest!!.rateType == RateType.TIERED && revision.interest!!.tiers.size > 1 ->
                listOf(InterestShadowResult.Unmapped("tiered-rate"))
            else -> {
                val rule = scheduledRule(revision, Action.AccrueInterest)
                if (rule == null) {
                    listOf(InterestShadowResult.Unmapped("accrue-rule"))
                } else {
                    val state = ProductState(
                        currency = legacy.currency,
                        balance = legacy.balance,
                        openedOn = legacy.accrualDate,
                    )
                    val posts = evaluate(revision, rule, state, legacy.accrualDate).filter { it.ruleId == termsRuleId }
                    val ir = sum(posts, AccountingClass.INTEREST_EXPENSE, PostingDirection.DEBIT)
                    listOf(compared(termsRuleId, legacy.accruedAmount, ir))
                }
            }
        }

    private fun capitalisation(revision: ProductRevisionIr, legacy: ShadowLegacyCapitalization): InterestShadowResult {
        val rule = scheduledRule(revision, Action.CapitaliseInterest)
            ?: return InterestShadowResult.Unmapped("capitalise-rule")
        val state = ProductState(
            currency = legacy.currency,
            balance = BigDecimal.ZERO,
            accruedInterest = legacy.totalAccrued,
            openedOn = legacy.periodTo,
        )
        val posts = evaluate(revision, rule, state, legacy.periodTo).filter { it.ruleId == rule.ruleId }
        val ir = sum(posts, AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.CREDIT)
        return compared(rule.ruleId, legacy.grossAmount, ir)
    }

    /** IR constructs the legacy path has no counterpart for; reported so they cannot pass as agreement. */
    private fun extras(revision: ProductRevisionIr): List<InterestShadowResult> =
        listOfNotNull(revision.interest?.bonus?.let { InterestShadowResult.Unmapped("bonus") })

    private fun scheduledRule(revision: ProductRevisionIr, action: Action): HookRule? =
        revision.hooks[Hook.SCHEDULED].orEmpty().firstOrNull { it.action == action && it.trigger != null }

    private fun evaluate(
        revision: ProductRevisionIr,
        rule: HookRule,
        state: ProductState,
        on: LocalDate,
    ): List<Instruction.Post> {
        val clock = ProductClock(on.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        val out = interpreter.evaluate(revision, state, ProductEvent(Hook.SCHEDULED, tag = rule.trigger), clock)
        val posts = out.filterIsInstance<Instruction.Post>()
        if (posts.any { !it.isBalancedPerCurrency() }) {
            throw ProductEvaluationException("rule ${rule.ruleId} produced an unbalanced Post")
        }
        return posts
    }

    private fun sum(posts: List<Instruction.Post>, cls: AccountingClass, dir: PostingDirection): BigDecimal = posts
        .flatMap { it.lines }
        .filter { it.accountingClass == cls && it.direction == dir }
        .fold(BigDecimal.ZERO) { a, l -> a + l.amount }

    /** Bit-exact: equal numerically, no tolerance (a stored NUMERIC(20,6) and a scale-6 result agree in scale too). */
    private fun compared(ruleId: String, legacy: BigDecimal, ir: BigDecimal) =
        InterestShadowResult.Compared(ruleId, legacy.compareTo(ir) != 0, legacy, ir)
}
