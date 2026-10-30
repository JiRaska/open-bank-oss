// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One branch per construct of the closed IR v1 grammar (ADR-0352 §1): the size of these `when`s is the
// size of the language, and keeping each construct's checks in one place is what makes it reviewable.
@file:Suppress("CyclomaticComplexMethod", "LongMethod", "TooManyFunctions", "SpreadOperator")

package com.openbank.libs.product.ir

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import com.openbank.libs.product.ir.ProductIrValidationException.Problem
import java.math.BigDecimal

/**
 * Parses and validates an IR v1 document (ADR-0352 §1–§2) into [ProductRevisionIr], computing its
 * [ProductRevisionIr.revisionHash] over the canonical form (§4).
 *
 * The validator is the control, not review: it rejects unknown keys, missing or duplicate `ruleId`s,
 * every [IrLimits] violation, a precision beyond the canonical scale, a `Reject` outside
 * `prePosting`, a `trigger` no `Schedule` produces (or a `Schedule` nothing consumes), and a
 * same-cycle `Schedule` cycle in the hook graph. The JSON Schema in `product-ir/v1.schema.json`
 * states the same structural rules for tooling that cannot run Kotlin; the two are kept in step by
 * `ProductIrSchemaAgreementTest`.
 */
object ProductIrParser {

    fun parse(json: String): ProductRevisionIr = fromTree(IrJson.parse(json))

    fun fromTree(tree: Any?): ProductRevisionIr {
        val ctx = Ctx()
        val root = ctx.obj(tree, "")
        val unhashed = ctx.revision(root)
        ctx.failIfProblems()
        val hash = ProductIrCanonical.revisionHash(unhashed)
        return unhashed.copy(revisionHash = hash)
    }

    private class Ctx {
        val problems = ArrayList<Problem>()

        fun problem(path: String, msg: String) {
            problems += Problem(path.ifEmpty { "/" }, msg)
        }

        fun failIfProblems() {
            if (problems.isNotEmpty()) throw ProductIrValidationException(problems.toList())
        }

        @Suppress("UNCHECKED_CAST")
        fun obj(v: Any?, path: String): Map<String, Any?> {
            if (v !is Map<*, *>) {
                problem(path, "expected an object")
                return emptyMap()
            }
            return v as Map<String, Any?>
        }

        fun known(o: Map<String, Any?>, path: String, vararg keys: String) {
            val allowed = keys.toSet()
            o.keys.filter { it !in allowed }.forEach { problem("$path/$it", "unknown key") }
        }

        fun str(o: Map<String, Any?>, path: String, key: String, required: Boolean = true): String? {
            val v = o[key]
            if (v == null) {
                if (required) problem("$path/$key", "required")
                return null
            }
            if (v !is String || v.isEmpty()) {
                problem("$path/$key", "expected a non-empty string")
                return null
            }
            return v
        }

        fun int(o: Map<String, Any?>, path: String, key: String, min: Int, max: Int, required: Boolean = true): Int? {
            val v = o[key]
            if (v == null) {
                if (required) problem("$path/$key", "required")
                return null
            }
            val d = when (v) {
                is BigDecimal -> v
                is Int, is Long -> BigDecimal.valueOf((v as Number).toLong())
                else -> null
            }
            if (d == null || d.stripTrailingZeros().scale() > 0) {
                problem("$path/$key", "expected an integer")
                return null
            }
            val n = d.toBigInteger()
            if (n < BigDecimal(min).toBigInteger() || n > BigDecimal(max).toBigInteger()) {
                problem("$path/$key", "must be between $min and $max")
                return null
            }
            return n.toInt()
        }

        /** A decimal authored as a JSON number or a decimal string; never more precise than [scale]. */
        fun decimal(
            o: Map<String, Any?>,
            path: String,
            key: String,
            scale: Int,
            required: Boolean = true,
        ): BigDecimal? {
            val v = o[key]
            if (v == null) {
                if (required) problem("$path/$key", "required")
                return null
            }
            val d = when (v) {
                is BigDecimal -> v
                is Int, is Long -> BigDecimal.valueOf((v as Number).toLong())
                is String -> if (DECIMAL.matches(v)) BigDecimal(v) else null
                else -> null
            }
            if (d == null) {
                problem("$path/$key", "expected a decimal (number or decimal string)")
                return null
            }
            if (d.stripTrailingZeros().scale() > scale) {
                problem("$path/$key", "more than $scale decimal places; the canonical scale is $scale")
                return null
            }
            return d
        }

        inline fun <reified E : Enum<E>> enum(
            o: Map<String, Any?>,
            path: String,
            key: String,
            required: Boolean = true,
        ): E? {
            val s = str(o, path, key, required) ?: return null
            return enumValues<E>().firstOrNull { it.name == s } ?: run {
                problem("$path/$key", "must be one of ${enumValues<E>().joinToString { it.name }}")
                null
            }
        }

        fun list(o: Map<String, Any?>, path: String, key: String, max: Int, required: Boolean = false): List<Any?> {
            val v = o[key]
            if (v == null) {
                if (required) problem("$path/$key", "required")
                return emptyList()
            }
            if (v !is List<*>) {
                problem("$path/$key", "expected an array")
                return emptyList()
            }
            if (v.size > max) problem("$path/$key", "more than $max items (limit)")
            return v
        }

        fun ruleId(o: Map<String, Any?>, path: String): String {
            val s = str(o, path, "ruleId") ?: return "<missing@$path>"
            if (s.length > IrLimits.MAX_RULE_ID_LENGTH || !IrLimits.RULE_ID_PATTERN.matches(s)) {
                problem(
                    "$path/ruleId",
                    "must match ${IrLimits.RULE_ID_PATTERN.pattern} " +
                        "and be at most ${IrLimits.MAX_RULE_ID_LENGTH} chars",
                )
            }
            return s
        }

        // --- sections -------------------------------------------------------------------------

        fun revision(root: Map<String, Any?>): ProductRevisionIr {
            known(root, "", "irVersion", "productCode", "currency", "interest", "fees", "termDeposit", "hooks")
            val irVersion = int(root, "", "irVersion", 1, Int.MAX_VALUE) ?: 0
            if (irVersion != 0 &&
                irVersion !in IrVersion.SUPPORTED
            ) {
                problem("/irVersion", "unsupported irVersion $irVersion; supported: ${IrVersion.SUPPORTED}")
            }
            val productCode = str(root, "", "productCode") ?: ""
            val currency = str(root, "", "currency") ?: ""
            if (currency.isNotEmpty() &&
                runCatching { CurrencyCode.of(currency) }.isFailure
            ) {
                problem("/currency", "not an ISO 4217 code")
            }
            val interest = root["interest"]?.let { interest(obj(it, "/interest")) }
            val fees = list(root, "", "fees", IrLimits.MAX_FEES).mapIndexed { i, f ->
                fee(obj(f, "/fees/$i"), "/fees/$i")
            }
            val term = root["termDeposit"]?.let { termDeposit(obj(it, "/termDeposit")) }
            val hooks = hooks(obj(root["hooks"] ?: emptyMap<String, Any?>(), "/hooks"))
            val rev =
                ProductRevisionIr(irVersion, productCode, currency, interest, fees, term, hooks, revisionHash = "")
            crossChecks(rev)
            return rev
        }

        fun interest(o: Map<String, Any?>): InterestTerms {
            val p = "/interest"
            known(o, p, *INTEREST_KEYS)
            val id = ruleId(o, p)
            val rateType = enum<RateType>(o, p, "rateType")
            val rate = decimal(o, p, "rate", IrLimits.RATE_SCALE, required = rateType == RateType.FIXED)
            val tiers = list(o, p, "tiers", IrLimits.MAX_TIERS, required = rateType == RateType.TIERED).mapIndexed {
                    i,
                    t,
                ->
                tier(obj(t, "$p/tiers/$i"), "$p/tiers/$i")
            }
            if (rateType == RateType.FIXED && o["tiers"] != null) problem("$p/tiers", "not allowed for rateType FIXED")
            if (rateType == RateType.TIERED && o["rate"] != null) problem("$p/rate", "not allowed for rateType TIERED")
            if (rateType == RateType.TIERED) checkTiers(tiers, "$p/tiers")
            val accrualCadence = enum<Cadence>(o, p, "accrualCadence") ?: Cadence.DAILY
            val capCadence = enum<Cadence>(o, p, "capitalisationCadence") ?: Cadence.MONTHLY
            val accrualRounding = enum<RoundingPolicy>(o, p, "accrualRounding") ?: RoundingPolicy.INTEREST_ACCRUAL
            val postingRounding = enum<RoundingPolicy>(o, p, "postingRounding") ?: RoundingPolicy.LEDGER_POSTING
            if (accrualRounding.fixedScale == null) problem("$p/accrualRounding", "must name a fixed-scale policy")
            val bonus = o["bonus"]?.let { bonus(obj(it, "$p/bonus"), "$p/bonus") }
            return InterestTerms(
                id, rateType ?: RateType.FIXED, rate, tiers,
                enum<IrDayCount>(
                    o,
                    p,
                    "dayCount",
                ) ?: IrDayCount.ACT_365_FIXED,
                accrualCadence, capCadence, accrualRounding, postingRounding, bonus,
            )
        }

        fun tier(o: Map<String, Any?>, p: String): Tier {
            known(o, p, "from", "to", "rate")
            val from = decimal(o, p, "from", IrLimits.AMOUNT_SCALE) ?: BigDecimal.ZERO
            val to = decimal(o, p, "to", IrLimits.AMOUNT_SCALE, required = false)
            val rate = decimal(o, p, "rate", IrLimits.RATE_SCALE) ?: BigDecimal.ZERO
            return Tier(from, to, rate)
        }

        fun checkTiers(tiers: List<Tier>, p: String) {
            if (tiers.isEmpty()) {
                problem(p, "at least one tier")
                return
            }
            if (tiers.first().from.signum() != 0) problem("$p/0/from", "the first tier must start at 0")
            tiers.forEachIndexed { i, t ->
                val to = t.to
                if (to != null && to <= t.from) problem("$p/$i/to", "must exceed from")
                if (i < tiers.lastIndex) {
                    if (to == null) {
                        problem("$p/$i/to", "only the last tier may be open-ended")
                    } else if (tiers[i + 1].from.compareTo(to) !=
                        0
                    ) {
                        problem("$p/${i + 1}/from", "tiers must be contiguous (expected $to)")
                    }
                } else if (to != null) {
                    problem("$p/$i/to", "the last tier must be open-ended (omit to)")
                }
            }
        }

        fun bonus(o: Map<String, Any?>, p: String): BonusRate {
            known(o, p, "ruleId", "rate", "predicate")
            val id = ruleId(o, p)
            val rate = decimal(o, p, "rate", IrLimits.RATE_SCALE) ?: BigDecimal.ZERO
            val pred = o["predicate"]?.let { predicate(it, "$p/predicate", 1) } ?: run {
                problem("$p/predicate", "required")
                Predicate.And(emptyList())
            }
            return BonusRate(id, rate, pred)
        }

        fun fee(o: Map<String, Any?>, p: String): FeeTerms {
            known(o, p, "ruleId", "feeCode", "amount", "cadence", "accountingClass", "waiver")
            val id = ruleId(o, p)
            val code = str(o, p, "feeCode") ?: ""
            val amount = decimal(o, p, "amount", IrLimits.AMOUNT_SCALE) ?: BigDecimal.ZERO
            if (amount.signum() < 0) problem("$p/amount", "must not be negative")
            val cadence = enum<Cadence>(o, p, "cadence") ?: Cadence.MONTHLY
            val cls = enum<AccountingClass>(o, p, "accountingClass") ?: AccountingClass.FEE_INCOME
            val waiver = o["waiver"]?.let { predicate(it, "$p/waiver", 1) }
            return FeeTerms(id, code, amount, cadence, cls, waiver)
        }

        fun termDeposit(o: Map<String, Any?>): TermDepositTerms {
            val p = "/termDeposit"
            known(o, p, "ruleId", "termMonths", "maturityAction", "earlyWithdrawal")
            val id = ruleId(o, p)
            val months = int(o, p, "termMonths", 1, IrLimits.MAX_TERM_MONTHS) ?: 1
            val action = enum<MaturityAction>(o, p, "maturityAction") ?: MaturityAction.CAPITALISE
            val early = o["earlyWithdrawal"]?.let { e ->
                val eo = obj(e, "$p/earlyWithdrawal")
                val ep = "$p/earlyWithdrawal"
                known(eo, ep, "ruleId", "kind", "rate", "fraction")
                val eid = ruleId(eo, ep)
                val kind = enum<PenaltyKind>(eo, ep, "kind")
                val rate = decimal(eo, ep, "rate", IrLimits.RATE_SCALE, required = kind == PenaltyKind.RATE)
                val fraction = decimal(
                    eo,
                    ep,
                    "fraction",
                    IrLimits.RATE_SCALE,
                    required =
                    kind == PenaltyKind.FORFEITED_INTEREST_FRACTION,
                )
                if (kind == PenaltyKind.RATE &&
                    eo["fraction"] != null
                ) {
                    problem("$ep/fraction", "not allowed for kind RATE")
                }
                if (kind == PenaltyKind.FORFEITED_INTEREST_FRACTION &&
                    eo["rate"] != null
                ) {
                    problem("$ep/rate", "not allowed for kind FORFEITED_INTEREST_FRACTION")
                }
                if (fraction != null &&
                    (fraction.signum() < 0 || fraction > BigDecimal.ONE)
                ) {
                    problem("$ep/fraction", "must be within [0, 1]")
                }
                EarlyWithdrawalPenalty(eid, kind ?: PenaltyKind.RATE, rate, fraction)
            }
            return TermDepositTerms(id, months, action, early)
        }

        fun hooks(o: Map<String, Any?>): Map<Hook, List<HookRule>> {
            val p = "/hooks"
            known(o, p, *HOOK_KEYS.keys.toTypedArray())
            val out = LinkedHashMap<Hook, List<HookRule>>()
            for ((key, hook) in HOOK_KEYS) {
                val rules = list(o, p, key, IrLimits.MAX_RULES_PER_HOOK).mapIndexed { i, r ->
                    hookRule(obj(r, "$p/$key/$i"), "$p/$key/$i", hook)
                }
                if (rules.isNotEmpty()) out[hook] = rules
            }
            return out
        }

        fun hookRule(o: Map<String, Any?>, p: String, hook: Hook): HookRule {
            known(o, p, "ruleId", "trigger", "when", "action")
            val id = ruleId(o, p)
            val trigger = str(o, p, "trigger", required = hook == Hook.SCHEDULED)
            if (trigger != null && hook != Hook.SCHEDULED) problem("$p/trigger", "only a scheduled rule has a trigger")
            val whenP = o["when"]?.let { predicate(it, "$p/when", 1) }
            val action = action(obj(o["action"], "$p/action"), "$p/action", hook)
            return HookRule(id, trigger, whenP, action)
        }

        fun action(o: Map<String, Any?>, p: String, hook: Hook): Action {
            val type = str(o, p, "type") ?: return Action.AccrueInterest
            return when (type) {
                "SCHEDULE" -> {
                    known(o, p, "type", "hook", "tag", "cadence")
                    val target =
                        str(o, p, "hook")?.let {
                            HOOK_KEYS[it]
                                ?: run {
                                    problem("$p/hook", "must be one of ${HOOK_KEYS.keys}")
                                    null
                                }
                        }
                            ?: Hook.SCHEDULED
                    val tag = str(o, p, "tag") ?: ""
                    if (tag.isNotEmpty() && !TAG.matches(tag)) problem("$p/tag", "must match ${TAG.pattern}")
                    val cadence = enum<Cadence>(o, p, "cadence") ?: Cadence.DAILY
                    if (target != Hook.SCHEDULED &&
                        target != Hook.ON_CLOSE
                    ) {
                        problem("$p/hook", "a schedule may target scheduled or onClose only")
                    }
                    Action.Schedule(target, tag, cadence)
                }
                "ACCRUE_INTEREST" -> {
                    known(o, p, "type")
                    Action.AccrueInterest
                }
                "CAPITALISE_INTEREST" -> {
                    known(o, p, "type")
                    Action.CapitaliseInterest
                }
                "CHARGE_FEE" -> {
                    known(o, p, "type", "feeRuleId")
                    Action.ChargeFee(str(o, p, "feeRuleId") ?: "")
                }
                "REJECT" -> {
                    known(o, p, "type", "reasonCode")
                    if (hook != Hook.PRE_POSTING) problem("$p/type", "REJECT is allowed on prePosting only")
                    val code = str(o, p, "reasonCode") ?: ""
                    if (code.isNotEmpty() &&
                        !REASON.matches(code)
                    ) {
                        problem("$p/reasonCode", "must match ${REASON.pattern}")
                    }
                    Action.Reject(code)
                }
                "NOTIFY" -> {
                    known(o, p, "type", "templateId", "variables")
                    val template = str(o, p, "templateId") ?: ""
                    val vars = obj(o["variables"] ?: emptyMap<String, Any?>(), "$p/variables")
                    if (vars.size >
                        IrLimits.MAX_NOTIFY_VARIABLES
                    ) {
                        problem("$p/variables", "more than ${IrLimits.MAX_NOTIFY_VARIABLES} variables (limit)")
                    }
                    val typed = LinkedHashMap<String, String>()
                    vars.forEach { (k, v) ->
                        if (!VAR_NAME.matches(
                                k,
                            )
                        ) {
                            problem("$p/variables/$k", "variable names must match ${VAR_NAME.pattern}")
                        }
                        if (v is String) typed[k] = v else problem("$p/variables/$k", "expected a string")
                    }
                    Action.Notify(template, typed)
                }
                "REPRICE" -> {
                    known(o, p, "type", "parameter", "value")
                    val param = str(o, p, "parameter") ?: ""
                    if (param.isNotEmpty() &&
                        param !in REPRICEABLE
                    ) {
                        problem("$p/parameter", "must be one of $REPRICEABLE")
                    }
                    Action.Reprice(param, decimal(o, p, "value", IrLimits.RATE_SCALE) ?: BigDecimal.ZERO)
                }
                "MATURE" -> {
                    known(o, p, "type")
                    Action.Mature
                }
                "EARLY_WITHDRAWAL_PENALTY" -> {
                    known(o, p, "type")
                    if (hook !=
                        Hook.PRE_POSTING
                    ) {
                        problem("$p/type", "EARLY_WITHDRAWAL_PENALTY is allowed on prePosting only")
                    }
                    Action.EarlyWithdrawalPenalty
                }
                else -> {
                    problem("$p/type", "unknown action type '$type'")
                    Action.AccrueInterest
                }
            }
        }

        fun predicate(v: Any?, p: String, depth: Int): Predicate {
            if (depth > IrLimits.MAX_PREDICATE_DEPTH) {
                problem(p, "predicate deeper than ${IrLimits.MAX_PREDICATE_DEPTH} (limit)")
                return Predicate.And(emptyList())
            }
            val o = obj(v, p)
            val type = str(o, p, "type") ?: return Predicate.And(emptyList())
            return when (type) {
                "comparison" -> {
                    known(o, p, "type", "attribute", "operator", "threshold", "textValue", "currency")
                    val attr = enum<WaiveAttribute>(o, p, "attribute")
                    val op = enum<WaiveOperator>(o, p, "operator") ?: WaiveOperator.EQ
                    val threshold = decimal(o, p, "threshold", IrLimits.AMOUNT_SCALE, required = attr?.numeric == true)
                    val text = str(o, p, "textValue", required = attr?.numeric == false)
                    val ccy = str(o, p, "currency", required = false)
                    if (attr?.numeric == false &&
                        op != WaiveOperator.EQ &&
                        op != WaiveOperator.NEQ
                    ) {
                        problem("$p/operator", "a text attribute allows EQ or NEQ only")
                    }
                    if (attr?.numeric == false &&
                        (o["threshold"] != null || o["currency"] != null)
                    ) {
                        problem(p, "threshold/currency are for numeric attributes")
                    }
                    if (attr?.numeric == true &&
                        o["textValue"] != null
                    ) {
                        problem("$p/textValue", "textValue is for text attributes")
                    }
                    Predicate.Comparison(attr ?: WaiveAttribute.BALANCE, op, threshold, text, ccy)
                }
                "postingComparison" -> {
                    known(o, p, "type", "attribute", "operator", "threshold")
                    Predicate.PostingComparison(
                        enum<PostingAttribute>(o, p, "attribute") ?: PostingAttribute.POSTING_AMOUNT,
                        enum<WaiveOperator>(o, p, "operator") ?: WaiveOperator.EQ,
                        decimal(o, p, "threshold", IrLimits.AMOUNT_SCALE) ?: BigDecimal.ZERO,
                    )
                }
                "noEvents" -> {
                    known(o, p, "type", "kind", "window")
                    Predicate.NoEvents(enum<EventKind>(o, p, "kind") ?: EventKind.WITHDRAWAL, window(o, p))
                }
                "countEvents" -> {
                    known(o, p, "type", "kind", "window", "operator", "count")
                    Predicate.CountEvents(
                        enum<EventKind>(o, p, "kind") ?: EventKind.WITHDRAWAL,
                        window(o, p),
                        enum<WaiveOperator>(o, p, "operator") ?: WaiveOperator.EQ,
                        int(o, p, "count", 0, Int.MAX_VALUE) ?: 0,
                    )
                }
                "sumEvents" -> {
                    known(o, p, "type", "kind", "window", "operator", "amount")
                    Predicate.SumEvents(
                        enum<EventKind>(o, p, "kind") ?: EventKind.WITHDRAWAL,
                        window(o, p),
                        enum<WaiveOperator>(o, p, "operator") ?: WaiveOperator.EQ,
                        decimal(o, p, "amount", IrLimits.AMOUNT_SCALE) ?: BigDecimal.ZERO,
                    )
                }
                "balanceMin" -> {
                    known(o, p, "type", "window", "operator", "amount")
                    Predicate.BalanceMin(
                        window(o, p),
                        enum<WaiveOperator>(o, p, "operator") ?: WaiveOperator.EQ,
                        decimal(o, p, "amount", IrLimits.AMOUNT_SCALE) ?: BigDecimal.ZERO,
                    )
                }
                "and", "or" -> {
                    known(o, p, "type", "operands")
                    val ops = list(o, p, "operands", IrLimits.MAX_AND_OR_OPERANDS, required = true).mapIndexed { i, x ->
                        predicate(
                            x,
                            "$p/operands/$i",
                            depth + 1,
                        )
                    }
                    if (ops.isEmpty()) problem("$p/operands", "at least one operand")
                    if (type == "and") Predicate.And(ops) else Predicate.Or(ops)
                }
                "not" -> {
                    known(o, p, "type", "operand")
                    Predicate.Not(predicate(o["operand"], "$p/operand", depth + 1))
                }
                else -> {
                    problem("$p/type", "unknown predicate type '$type'")
                    Predicate.And(emptyList())
                }
            }
        }

        fun window(o: Map<String, Any?>, p: String): Window {
            val w = obj(o["window"], "$p/window")
            known(w, "$p/window", "days")
            return Window(int(w, "$p/window", "days", 1, IrLimits.MAX_WINDOW_DAYS) ?: 1)
        }

        // --- cross-section checks ------------------------------------------------------------

        fun crossChecks(rev: ProductRevisionIr) {
            val ids = rev.allRuleIds
            ids.groupingBy {
                it
            }.eachCount().filter { it.value > 1 }.keys.forEach { problem("/", "duplicate ruleId '$it'") }

            val feeIds = rev.fees.map { it.ruleId }.toSet()
            val produced = HashSet<String>()
            val consumed = HashSet<String>()
            val sameCycleEdges = ArrayList<Pair<Hook, Hook>>()
            rev.hooks.forEach { (hook, rules) ->
                rules.forEachIndexed { i, r ->
                    val p = "/hooks/${HOOK_KEYS.entries.first { it.value == hook }.key}/$i"
                    when (val a = r.action) {
                        is Action.Schedule -> {
                            produced += a.tag
                            if (a.cadence == Cadence.ON_EVENT) sameCycleEdges += hook to a.hook
                            if (a.cadence == Cadence.ON_EVENT &&
                                a.hook == hook
                            ) {
                                problem("$p/action", "a same-cycle Schedule may not target the hook that emits it")
                            }
                            if (a.cadence == Cadence.AT_MATURITY &&
                                rev.termDeposit == null
                            ) {
                                problem("$p/action/cadence", "AT_MATURITY needs a termDeposit section")
                            }
                        }
                        is Action.ChargeFee -> if (a.feeRuleId !in
                            feeIds
                        ) {
                            problem("$p/action/feeRuleId", "no fee with ruleId '${a.feeRuleId}'")
                        }
                        Action.AccrueInterest, Action.CapitaliseInterest -> if (rev.interest ==
                            null
                        ) {
                            problem("$p/action", "needs an interest section")
                        }
                        Action.Mature, Action.EarlyWithdrawalPenalty -> if (rev.termDeposit ==
                            null
                        ) {
                            problem("$p/action", "needs a termDeposit section")
                        }
                        is Action.Reprice -> if (rev.interest == null) problem("$p/action", "needs an interest section")
                        is Action.Reject, is Action.Notify -> Unit
                    }
                    r.trigger?.let { consumed += it }
                }
            }
            (consumed - produced).forEach {
                problem("/hooks/scheduled", "trigger '$it' is produced by no Schedule action")
            }
            (produced - consumed).forEach { problem("/hooks", "Schedule tag '$it' is consumed by no scheduled rule") }
            if (hasCycle(sameCycleEdges)) problem("/hooks", "same-cycle (ON_EVENT) Schedule edges form a cycle")
        }

        private fun hasCycle(edges: List<Pair<Hook, Hook>>): Boolean {
            val adj = edges.groupBy({ it.first }, { it.second })
            val state = HashMap<Hook, Int>() // 1 visiting, 2 done
            fun dfs(h: Hook): Boolean {
                when (state[h]) {
                    1 -> return true
                    2 -> return false
                }
                state[h] = 1
                if (adj[h].orEmpty().any { dfs(it) }) return true
                state[h] = 2
                return false
            }
            return adj.keys.any { dfs(it) }
        }
    }

    /** IR key of each hook, in canonical order (`onOpen` … `onClose`). */
    val HOOK_KEYS: Map<String, Hook> = linkedMapOf(
        "onOpen" to Hook.ON_OPEN,
        "prePosting" to Hook.PRE_POSTING,
        "postPosting" to Hook.POST_POSTING,
        "scheduled" to Hook.SCHEDULED,
        "onParameterChange" to Hook.ON_PARAMETER_CHANGE,
        "onClose" to Hook.ON_CLOSE,
    )
    private val INTEREST_KEYS = arrayOf(
        "ruleId", "rateType", "rate", "tiers", "dayCount", "accrualCadence",
        "capitalisationCadence", "accrualRounding", "postingRounding", "bonus",
    )
    internal val REPRICEABLE: Set<String> = setOf("interest.rate", "interest.bonus.rate")
    private val DECIMAL = Regex("^-?[0-9]+(\\.[0-9]+)?$")
    private val TAG = Regex("^[A-Z][A-Z0-9_]{0,31}$")
    private val REASON = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val VAR_NAME = Regex("^[a-z][A-Za-z0-9]{0,31}$")
}
