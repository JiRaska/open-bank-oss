// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One canonical-form function per IR section, mirroring ProductIrParser.
@file:Suppress("TooManyFunctions")

package com.openbank.libs.product.ir

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.audit.CanonicalJson
import java.math.BigDecimal

/**
 * Canonical form and hash of a revision (ADR-0352 §4). Keys are ordered by UTF-16 code unit and
 * whitespace is dropped by [CanonicalJson] (RFC 8785, the audit chain's writer); every decimal is
 * written as a **fixed-scale decimal string** — rates and fractions at [IrLimits.RATE_SCALE],
 * amounts and thresholds at [IrLimits.AMOUNT_SCALE] — so `0.025` and `0.0250` are one revision.
 * `revisionHash` is the SHA-256 hex of the canonical UTF-8 bytes and is what every instruction
 * carries. The hash itself is never part of the hashed form.
 */
object ProductIrCanonical {

    fun canonicalJson(rev: ProductRevisionIr): String = CanonicalJson.write(tree(rev))

    /**
     * Absent and null are one state in the IR, so the canonical form drops null members and empty
     * optional arrays (a FIXED rate has no `tiers`): the canonical document must re-parse to the
     * same revision, and the parser treats a present `tiers` on a FIXED rate as an error.
     */
    private fun prune(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.filter { it.value != null }.associate { it.key to prune(it.value) }
        is List<*> -> v.map { prune(it) }
        else -> v
    }

    fun revisionHash(rev: ProductRevisionIr): String = AuditChain.sha256Hex(canonicalJson(rev))

    fun rate(v: BigDecimal): String = v.setScale(IrLimits.RATE_SCALE).toPlainString()

    fun amount(v: BigDecimal): String = v.setScale(IrLimits.AMOUNT_SCALE).toPlainString()

    fun instructionAmount(v: BigDecimal): String = v.setScale(IrLimits.INSTRUCTION_AMOUNT_SCALE).toPlainString()

    @Suppress("UNCHECKED_CAST")
    fun tree(rev: ProductRevisionIr): Map<String, Any?> = prune(rawTree(rev)) as Map<String, Any?>

    private fun rawTree(rev: ProductRevisionIr): Map<String, Any?> = mapOf(
        "irVersion" to rev.irVersion,
        "productCode" to rev.productCode,
        "currency" to rev.currency,
        "interest" to rev.interest?.let { interest(it) },
        "fees" to rev.fees.map { fee(it) },
        "termDeposit" to rev.termDeposit?.let { term(it) },
        "hooks" to ProductIrParser.HOOK_KEYS.mapNotNull { (key, hook) ->
            rev.hooks[hook]?.takeIf { it.isNotEmpty() }?.let { key to it.map { r -> hookRule(r) } }
        }.toMap(),
    )

    private fun interest(i: InterestTerms): Map<String, Any?> = mapOf(
        "ruleId" to i.ruleId,
        "rateType" to i.rateType,
        "rate" to i.rate?.let { rate(it) },
        "tiers" to
            i.tiers.takeIf { it.isNotEmpty() }?.map { t ->
                mapOf(
                    "from" to amount(t.from),
                    "to" to t.to?.let { amount(it) },
                    "rate" to rate(t.rate),
                )
            },
        "dayCount" to i.dayCount,
        "accrualCadence" to i.accrualCadence,
        "capitalisationCadence" to i.capitalisationCadence,
        "accrualRounding" to i.accrualRounding,
        "postingRounding" to i.postingRounding,
        "bonus" to
            i.bonus?.let {
                mapOf("ruleId" to it.ruleId, "rate" to rate(it.rate), "predicate" to predicate(it.predicate))
            },
    )

    private fun fee(f: FeeTerms): Map<String, Any?> = mapOf(
        "ruleId" to f.ruleId,
        "feeCode" to f.feeCode,
        "amount" to amount(f.amount),
        "cadence" to f.cadence,
        "accountingClass" to f.accountingClass,
        "waiver" to f.waiver?.let { predicate(it) },
    )

    private fun term(t: TermDepositTerms): Map<String, Any?> = mapOf(
        "ruleId" to t.ruleId,
        "termMonths" to t.termMonths,
        "maturityAction" to t.maturityAction,
        "earlyWithdrawal" to t.earlyWithdrawal?.let { e ->
            mapOf(
                "ruleId" to e.ruleId,
                "kind" to e.kind,
                "rate" to e.rate?.let { rate(it) },
                "fraction" to e.fraction?.let { rate(it) },
            )
        },
    )

    private fun hookRule(r: HookRule): Map<String, Any?> = mapOf(
        "ruleId" to r.ruleId,
        "trigger" to r.trigger,
        "when" to r.whenPredicate?.let { predicate(it) },
        "action" to action(r.action),
    )

    private fun action(a: Action): Map<String, Any?> = when (a) {
        is Action.Schedule -> mapOf(
            "type" to "SCHEDULE",
            "hook" to ProductIrParser.HOOK_KEYS.entries.first { it.value == a.hook }.key,
            "tag" to a.tag,
            "cadence" to a.cadence,
        )
        Action.AccrueInterest -> mapOf("type" to "ACCRUE_INTEREST")
        Action.CapitaliseInterest -> mapOf("type" to "CAPITALISE_INTEREST")
        is Action.ChargeFee -> mapOf("type" to "CHARGE_FEE", "feeRuleId" to a.feeRuleId)
        is Action.Reject -> mapOf("type" to "REJECT", "reasonCode" to a.reasonCode)
        is Action.Notify -> mapOf("type" to "NOTIFY", "templateId" to a.templateId, "variables" to a.variables)
        is Action.Reprice -> mapOf("type" to "REPRICE", "parameter" to a.parameter, "value" to rate(a.value))
        Action.Mature -> mapOf("type" to "MATURE")
        Action.EarlyWithdrawalPenalty -> mapOf("type" to "EARLY_WITHDRAWAL_PENALTY")
    }

    fun predicate(p: Predicate): Map<String, Any?> = when (p) {
        is Predicate.Comparison -> mapOf(
            "type" to "comparison",
            "attribute" to p.attribute,
            "operator" to p.operator,
            "threshold" to p.threshold?.let { amount(it) },
            "textValue" to p.textValue,
            "currency" to p.currency,
        )
        is Predicate.PostingComparison -> mapOf(
            "type" to "postingComparison",
            "attribute" to p.attribute,
            "operator" to p.operator,
            "threshold" to amount(p.threshold),
        )
        is Predicate.NoEvents -> mapOf("type" to "noEvents", "kind" to p.kind, "window" to window(p.window))
        is Predicate.CountEvents -> mapOf(
            "type" to "countEvents",
            "kind" to p.kind,
            "window" to window(p.window),
            "operator" to p.operator,
            "count" to p.count,
        )
        is Predicate.SumEvents -> mapOf(
            "type" to "sumEvents",
            "kind" to p.kind,
            "window" to window(p.window),
            "operator" to p.operator,
            "amount" to amount(p.amount),
        )
        is Predicate.BalanceMin -> mapOf(
            "type" to "balanceMin",
            "window" to window(p.window),
            "operator" to p.operator,
            "amount" to amount(p.amount),
        )
        is Predicate.And -> mapOf("type" to "and", "operands" to p.operands.map { predicate(it) })
        is Predicate.Or -> mapOf("type" to "or", "operands" to p.operands.map { predicate(it) })
        is Predicate.Not -> mapOf("type" to "not", "operand" to predicate(p.operand))
    }

    private fun window(w: Window): Map<String, Any?> = mapOf("days" to w.days)
}
