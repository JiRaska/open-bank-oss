// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The pure interpreter (ADR-0352 §3): `evaluate(revision, state, event, clock)` is a function of
 * its arguments and nothing else. Zero I/O, no clock but [ProductClock], no randomness, no
 * logging, no metrics. The same class runs in the production consumer, in simulation, in the CI
 * golden runner and behind Studio. Arithmetic is delegated to [ProductArithmetic]
 * (`openbank-libs-finance`); nothing here multiplies, scales or rounds.
 *
 * Runtime holds of the §2 limits: more than [IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION]
 * instructions, or a `Post` that is not balanced per currency, throws [ProductEvaluationException]
 * instead of returning — a defect in the interpreter or the revision, never a ledger error to find
 * later.
 */
class ProductInterpreter(private val arithmetic: ProductArithmetic) {

    fun evaluate(revision: ProductRevisionIr, state: ProductState, event: ProductEvent, clock: ProductClock): List<Instruction> {
        val run = Run(revision, state, event, clock.today())
        val rules = revision.hooks[event.hook].orEmpty()
        for (rule in rules) {
            if (event.hook == Hook.SCHEDULED && rule.trigger != event.tag) continue
            val gate = rule.whenPredicate
            if (gate != null && !PredicateEvaluator.test(gate, state, event.posting, run.today)) continue
            run.apply(rule)
            if (run.rejected) break
        }
        return run.finish()
    }

    private inner class Run(
        val revision: ProductRevisionIr,
        val state: ProductState,
        val event: ProductEvent,
        val today: LocalDate,
    ) {
        private val out = ArrayList<Instruction>()
        var rejected = false
            private set
        private val currency = CurrencyCode.of(state.currency)

        fun finish(): List<Instruction> = out.toList()

        private fun emit(i: Instruction) {
            if (i is Instruction.Post && !i.isBalancedPerCurrency()) {
                throw ProductEvaluationException("rule ${i.ruleId} produced an unbalanced Post: ${i.lines}")
            }
            if (out.size >= IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION) {
                throw ProductEvaluationException(
                    "evaluation of ${event.hook} exceeded ${IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION} instructions",
                )
            }
            out += i
        }

        private fun post(ruleId: String, narrative: String, vararg lines: PostingLine) {
            emit(Instruction.Post(ruleId, revision.revisionHash, revision.irVersion, lines.toList(), narrative))
        }

        private fun line(cls: AccountingClass, dir: PostingDirection, amount: BigDecimal) =
            PostingLine(cls, dir, amount, state.currency)

        fun apply(rule: HookRule) {
            when (val a = rule.action) {
                is Action.Schedule -> schedule(rule.ruleId, a)
                Action.AccrueInterest -> accrue()
                Action.CapitaliseInterest -> capitalise(rule.ruleId)
                is Action.ChargeFee -> chargeFee(rule.ruleId, a.feeRuleId)
                is Action.Reject -> {
                    emit(Instruction.Reject(rule.ruleId, revision.revisionHash, revision.irVersion, a.reasonCode))
                    rejected = true
                }
                is Action.Notify -> emit(
                    Instruction.Notify(rule.ruleId, revision.revisionHash, revision.irVersion, a.templateId, a.variables),
                )
                is Action.Reprice -> emit(
                    Instruction.Reprice(rule.ruleId, revision.revisionHash, revision.irVersion, a.parameter, a.value),
                )
                Action.Mature -> mature(rule.ruleId)
                Action.EarlyWithdrawalPenalty -> earlyWithdrawal(rule.ruleId)
            }
        }

        private fun schedule(ruleId: String, a: Action.Schedule) {
            val at = if (a.cadence == Cadence.AT_MATURITY) maturityDate() else null
            emit(Instruction.Schedule(ruleId, revision.revisionHash, revision.irVersion, a.hook, a.tag, a.cadence, at))
        }

        private fun maturityDate(): LocalDate = state.openedOn.plusMonths(revision.termDeposit!!.termMonths.toLong())

        private fun accrue() {
            val terms = revision.interest ?: return
            val base = when (terms.rateType) {
                RateType.FIXED -> arithmetic.dailyAccrual(
                    state.balance,
                    state.parameters[PARAM_RATE] ?: terms.rate!!,
                    terms.dayCount,
                    today,
                    terms.accrualRounding,
                )
                RateType.TIERED -> arithmetic.tieredDailyAccrual(state.balance, terms.tiers, terms.dayCount, today, terms.accrualRounding)
            }
            if (base.signum() > 0) accrualPost(terms.ruleId, base, "interest accrual")
            val bonus = terms.bonus ?: return
            if (!PredicateEvaluator.test(bonus.predicate, state, event.posting, today)) return
            val extra = arithmetic.dailyAccrual(
                state.balance,
                state.parameters[PARAM_BONUS_RATE] ?: bonus.rate,
                terms.dayCount,
                today,
                terms.accrualRounding,
            )
            if (extra.signum() > 0) accrualPost(bonus.ruleId, extra, "bonus interest accrual")
        }

        private fun accrualPost(ruleId: String, amount: BigDecimal, narrative: String) = post(
            ruleId,
            narrative,
            line(AccountingClass.INTEREST_EXPENSE, PostingDirection.DEBIT, amount),
            line(AccountingClass.ACCRUED_INTEREST_PAYABLE, PostingDirection.CREDIT, amount),
        )

        /** Posts the accrued interest into the deposit; returns the posted amount (0 when nothing to post). */
        private fun capitalise(ruleId: String): BigDecimal {
            val terms = revision.interest ?: return BigDecimal.ZERO
            val posted = arithmetic.capitalise(state.accruedInterest, currency, terms.postingRounding)
            if (posted.signum() <= 0) return BigDecimal.ZERO
            post(
                ruleId,
                "interest capitalisation",
                line(AccountingClass.ACCRUED_INTEREST_PAYABLE, PostingDirection.DEBIT, posted),
                line(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.CREDIT, posted),
            )
            return posted
        }

        private fun chargeFee(ruleId: String, feeRuleId: String) {
            val fee = revision.fees.first { it.ruleId == feeRuleId }
            val waiver = fee.waiver
            if (waiver != null && PredicateEvaluator.test(waiver, state, event.posting, today)) return
            if (fee.amount.signum() <= 0) return
            post(
                ruleId,
                "fee ${fee.feeCode}",
                line(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.DEBIT, fee.amount),
                line(fee.accountingClass, PostingDirection.CREDIT, fee.amount),
            )
        }

        private fun mature(ruleId: String) {
            val term = revision.termDeposit ?: return
            val posted = capitalise(ruleId)
            when (term.maturityAction) {
                MaturityAction.CAPITALISE -> Unit
                MaturityAction.PAY_OUT -> {
                    val total = state.balance + posted
                    if (total.signum() > 0) {
                        post(
                            ruleId,
                            "maturity pay-out",
                            line(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.DEBIT, total),
                            line(AccountingClass.SETTLEMENT, PostingDirection.CREDIT, total),
                        )
                    }
                }
                MaturityAction.ROLL_OVER -> emit(
                    Instruction.Schedule(
                        ruleId,
                        revision.revisionHash,
                        revision.irVersion,
                        Hook.SCHEDULED,
                        event.tag ?: "",
                        Cadence.AT_MATURITY,
                        today.plusMonths(term.termMonths.toLong()),
                    ),
                )
            }
        }

        private fun earlyWithdrawal(ruleId: String) {
            val term = revision.termDeposit ?: return
            val penalty = term.earlyWithdrawal ?: return
            val posting = event.posting ?: return
            if (posting.kind != EventKind.WITHDRAWAL || !today.isBefore(maturityDate())) return
            val postingRounding = revision.interest?.postingRounding ?: RoundingPolicy.LEDGER_POSTING
            when (penalty.kind) {
                PenaltyKind.RATE -> {
                    val amount = arithmetic.penaltyByRate(posting.amount, penalty.rate!!, currency, postingRounding)
                    if (amount.signum() > 0) {
                        post(
                            ruleId,
                            "early-withdrawal penalty",
                            line(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.DEBIT, amount),
                            line(AccountingClass.PENALTY_INCOME, PostingDirection.CREDIT, amount),
                        )
                    }
                }
                PenaltyKind.FORFEITED_INTEREST_FRACTION -> {
                    val amount = arithmetic.forfeitedInterest(state.accruedInterest, penalty.fraction!!, currency, postingRounding)
                    if (amount.signum() > 0) {
                        post(
                            ruleId,
                            "forfeited accrued interest",
                            line(AccountingClass.ACCRUED_INTEREST_PAYABLE, PostingDirection.DEBIT, amount),
                            line(AccountingClass.INTEREST_EXPENSE, PostingDirection.CREDIT, amount),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val PARAM_RATE = "interest.rate"
        const val PARAM_BONUS_RATE = "interest.bonus.rate"
    }
}
