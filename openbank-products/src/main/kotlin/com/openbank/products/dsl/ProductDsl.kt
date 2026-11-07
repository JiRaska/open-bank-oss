// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One builder per IR section; the file mirrors ProductIrParser's sections one to one.
@file:Suppress("TooManyFunctions")

package com.openbank.products.dsl

import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.IrDayCount
import com.openbank.libs.product.ir.IrVersion
import com.openbank.libs.product.ir.MaturityAction
import com.openbank.libs.product.ir.PenaltyKind
import com.openbank.libs.product.ir.PostingAttribute
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr

/**
 * The authoring DSL (ADR-0352 §5, §11.2): sugar that builds the IR v1 document TREE — the same
 * plain values `IrJson` yields from text — and hands it to [ProductIrParser], the one validator.
 * The DSL exists only at build time; the IR JSON it compiles to is the artifact and the only thing
 * the catalog stores. Nothing here computes, defaults or infers behaviour: every value an author
 * writes lands in the document verbatim, and a document the parser rejects has no product.
 *
 * Every `ruleId`-bearing construct records where it was declared ([ProductDefinition.sources]),
 * which is what `obp explain <ruleId>` maps a posting back to.
 */
class ProductDefinition internal constructor(
    val name: String,
    val tree: Map<String, Any?>,
    val sources: Map<String, SourceLocation>,
) {
    /** Validated revision, hash included. Throws `ProductIrValidationException` for an invalid document. */
    fun compile(): ProductRevisionIr = ProductIrParser.fromTree(tree)

    /** The artifact: canonical IR JSON (RFC 8785 key order, fixed-scale decimals), what `ir/<name>.json` holds. */
    fun canonicalJson(): String = ProductIrCanonical.canonicalJson(compile())
}

/** Kotlin source position of a DSL declaration, for `obp explain`. */
data class SourceLocation(val file: String, val line: Int) {
    override fun toString(): String = "$file:$line"
}

/** Entry point. [name] is the product's file name under `ir/` and `product-golden/`; [code] is the IR `productCode`. */
fun product(name: String, code: String, currency: String, block: ProductBuilder.() -> Unit): ProductDefinition {
    val b = ProductBuilder(code, currency).apply(block)
    return ProductDefinition(name, b.build(), b.sources.toMap())
}

@DslMarker
annotation class ProductIrDsl

@ProductIrDsl
class ProductBuilder internal constructor(private val code: String, private val currency: String) {
    private var interest: Map<String, Any?>? = null
    private val fees = ArrayList<Map<String, Any?>>()
    private var termDeposit: Map<String, Any?>? = null
    private val hooks = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
    internal val sources = LinkedHashMap<String, SourceLocation>()

    fun interest(ruleId: String, block: InterestBuilder.() -> Unit) {
        record(ruleId)
        interest = InterestBuilder(ruleId, this).apply(block).build()
    }

    fun fee(
        ruleId: String,
        feeCode: String,
        amount: String,
        cadence: Cadence,
        accountingClass: AccountingClass = AccountingClass.FEE_INCOME,
        waiver: Predicate? = null,
    ) {
        record(ruleId)
        fees += mapOf(
            "ruleId" to ruleId,
            "feeCode" to feeCode,
            "amount" to amount,
            "cadence" to cadence.name,
            "accountingClass" to accountingClass.name,
            "waiver" to waiver?.tree,
        )
    }

    fun termDeposit(
        ruleId: String,
        termMonths: Int,
        maturityAction: MaturityAction,
        block: TermBuilder.() -> Unit = {
        },
    ) {
        record(ruleId)
        termDeposit = TermBuilder(ruleId, termMonths, maturityAction, this).apply(block).build()
    }

    fun onOpen(block: HookBuilder.() -> Unit) = hook("onOpen", block)

    fun prePosting(block: HookBuilder.() -> Unit) = hook("prePosting", block)

    fun postPosting(block: HookBuilder.() -> Unit) = hook("postPosting", block)

    fun scheduled(block: HookBuilder.() -> Unit) = hook("scheduled", block)

    fun onParameterChange(block: HookBuilder.() -> Unit) = hook("onParameterChange", block)

    fun onClose(block: HookBuilder.() -> Unit) = hook("onClose", block)

    private fun hook(key: String, block: HookBuilder.() -> Unit) {
        val rules = hooks.getOrPut(key) { ArrayList() }
        rules += HookBuilder(this).apply(block).rules
    }

    internal fun record(ruleId: String) {
        sources.putIfAbsent(ruleId, callerLocation())
    }

    internal fun build(): Map<String, Any?> = mapOf(
        "irVersion" to IrVersion.V1,
        "productCode" to code,
        "currency" to currency,
        "interest" to interest,
        "fees" to fees.toList(),
        "termDeposit" to termDeposit,
        "hooks" to hooks.mapValues { it.value.toList() },
    ).filterValues { it != null }
}

@ProductIrDsl
class InterestBuilder internal constructor(private val ruleId: String, private val owner: ProductBuilder) {
    /** FIXED rate as a decimal string (`"0.0250"`); leave null and call [tiers] for TIERED. */
    var rate: String? = null
    var dayCount: IrDayCount = IrDayCount.ACT_365_FIXED
    var accrualCadence: Cadence = Cadence.DAILY
    var capitalisationCadence: Cadence = Cadence.MONTHLY

    /** Kernel `RoundingPolicy` names (ADR-0318); the IR carries the rule by name, never a (scale, mode) pair. */
    var accrualRounding: String = "INTEREST_ACCRUAL"
    var postingRounding: String = "LEDGER_POSTING"
    private val tiers = ArrayList<Map<String, Any?>>()
    private var bonus: Map<String, Any?>? = null

    /** One marginal band. [to] is null for the open-ended top band. */
    fun tier(from: String, to: String?, rate: String) {
        tiers += mapOf("from" to from, "to" to to, "rate" to rate)
    }

    fun bonus(ruleId: String, rate: String, predicate: Predicate) {
        owner.record(ruleId)
        bonus = mapOf("ruleId" to ruleId, "rate" to rate, "predicate" to predicate.tree)
    }

    internal fun build(): Map<String, Any?> = mapOf(
        "ruleId" to ruleId,
        "rateType" to if (tiers.isEmpty()) "FIXED" else "TIERED",
        "rate" to rate,
        "tiers" to tiers.takeIf { it.isNotEmpty() },
        "dayCount" to dayCount.name,
        "accrualCadence" to accrualCadence.name,
        "capitalisationCadence" to capitalisationCadence.name,
        "accrualRounding" to accrualRounding,
        "postingRounding" to postingRounding,
        "bonus" to bonus,
    ).filterValues { it != null }
}

@ProductIrDsl
class TermBuilder internal constructor(
    private val ruleId: String,
    private val termMonths: Int,
    private val maturityAction: MaturityAction,
    private val owner: ProductBuilder,
) {
    private var earlyWithdrawal: Map<String, Any?>? = null

    /** Penalty as a rate of the withdrawn amount. */
    fun penaltyRate(ruleId: String, rate: String) {
        owner.record(ruleId)
        earlyWithdrawal = mapOf("ruleId" to ruleId, "kind" to PenaltyKind.RATE.name, "rate" to rate)
    }

    /** Penalty as a forfeited fraction of the accrued interest. */
    fun forfeitedInterest(ruleId: String, fraction: String) {
        owner.record(ruleId)
        earlyWithdrawal =
            mapOf("ruleId" to ruleId, "kind" to PenaltyKind.FORFEITED_INTEREST_FRACTION.name, "fraction" to fraction)
    }

    internal fun build(): Map<String, Any?> = mapOf(
        "ruleId" to ruleId,
        "termMonths" to termMonths,
        "maturityAction" to maturityAction.name,
        "earlyWithdrawal" to earlyWithdrawal,
    ).filterValues { it != null }
}

@ProductIrDsl
class HookBuilder internal constructor(private val owner: ProductBuilder) {
    internal val rules = ArrayList<Map<String, Any?>>()

    /** The general form; the named helpers below are this with the action spelled out. */
    fun rule(ruleId: String, action: Map<String, Any?>, trigger: String? = null, whenPredicate: Predicate? = null) {
        owner.record(ruleId)
        rules += mapOf(
            "ruleId" to ruleId,
            "trigger" to trigger,
            "when" to whenPredicate?.tree,
            "action" to action,
        ).filterValues { it != null }
    }

    fun schedule(
        ruleId: String,
        tag: String,
        cadence: Cadence,
        hook: String = "scheduled",
        whenPredicate: Predicate? = null,
    ) = rule(
        ruleId,
        mapOf("type" to "SCHEDULE", "hook" to hook, "tag" to tag, "cadence" to cadence.name),
        null,
        whenPredicate,
    )

    fun accrueInterest(ruleId: String, trigger: String, whenPredicate: Predicate? = null) =
        rule(ruleId, mapOf("type" to "ACCRUE_INTEREST"), trigger, whenPredicate)

    fun capitaliseInterest(ruleId: String, trigger: String, whenPredicate: Predicate? = null) =
        rule(ruleId, mapOf("type" to "CAPITALISE_INTEREST"), trigger, whenPredicate)

    fun chargeFee(ruleId: String, trigger: String?, feeRuleId: String, whenPredicate: Predicate? = null) =
        rule(ruleId, mapOf("type" to "CHARGE_FEE", "feeRuleId" to feeRuleId), trigger, whenPredicate)

    fun reject(ruleId: String, whenPredicate: Predicate, reasonCode: String) =
        rule(ruleId, mapOf("type" to "REJECT", "reasonCode" to reasonCode), null, whenPredicate)

    fun notify(
        ruleId: String,
        templateId: String,
        variables: Map<String, String>,
        trigger: String? = null,
        whenPredicate: Predicate? = null,
    ) = rule(
        ruleId,
        mapOf("type" to "NOTIFY", "templateId" to templateId, "variables" to variables),
        trigger,
        whenPredicate,
    )

    fun reprice(
        ruleId: String,
        parameter: String,
        value: String,
        trigger: String? = null,
        whenPredicate: Predicate? = null,
    ) = rule(ruleId, mapOf("type" to "REPRICE", "parameter" to parameter, "value" to value), trigger, whenPredicate)

    fun mature(ruleId: String, trigger: String) = rule(ruleId, mapOf("type" to "MATURE"), trigger)

    fun earlyWithdrawalPenalty(ruleId: String, whenPredicate: Predicate? = null) =
        rule(ruleId, mapOf("type" to "EARLY_WITHDRAWAL_PENALTY"), null, whenPredicate)
}

/** A predicate tree in the closed language (ADR-0352 §1). Built only through the factory functions below. */
class Predicate internal constructor(val tree: Map<String, Any?>)

fun noEvents(kind: EventKind, days: Int) = Predicate(
    mapOf(
        "type" to "noEvents",
        "kind" to kind.name,
        "window" to mapOf("days" to days),
    ),
)

fun countEvents(kind: EventKind, days: Int, operator: WaiveOperator, count: Int) = Predicate(
    mapOf(
        "type" to "countEvents",
        "kind" to kind.name,
        "window" to mapOf("days" to days),
        "operator" to operator.name,
        "count" to count,
    ),
)

fun sumEvents(kind: EventKind, days: Int, operator: WaiveOperator, amount: String) = Predicate(
    mapOf(
        "type" to "sumEvents",
        "kind" to kind.name,
        "window" to mapOf("days" to days),
        "operator" to operator.name,
        "amount" to amount,
    ),
)

fun balanceMin(days: Int, operator: WaiveOperator, amount: String) = Predicate(
    mapOf(
        "type" to "balanceMin",
        "window" to mapOf("days" to days),
        "operator" to operator.name,
        "amount" to amount,
    ),
)

/** The ADR-0138 comparison grammar, unchanged (`WaivePredicate.Comparison`). */
fun comparison(
    attribute: WaiveAttribute,
    operator: WaiveOperator,
    threshold: String? = null,
    textValue: String? = null,
    currency: String? = null,
) = Predicate(
    mapOf(
        "type" to "comparison",
        "attribute" to attribute.name,
        "operator" to operator.name,
        "threshold" to threshold,
        "textValue" to textValue,
        "currency" to currency,
    ).filterValues { it != null },
)

fun postingComparison(attribute: PostingAttribute, operator: WaiveOperator, threshold: String) = Predicate(
    mapOf(
        "type" to "postingComparison",
        "attribute" to attribute.name,
        "operator" to operator.name,
        "threshold" to threshold,
    ),
)

fun allOf(vararg operands: Predicate) = Predicate(mapOf("type" to "and", "operands" to operands.map { it.tree }))

fun anyOf(vararg operands: Predicate) = Predicate(mapOf("type" to "or", "operands" to operands.map { it.tree }))

fun not(operand: Predicate) = Predicate(mapOf("type" to "not", "operand" to operand.tree))

/** The first stack frame outside this package: the product file and line that declared the construct. */
private fun callerLocation(): SourceLocation {
    val frame = Thread.currentThread().stackTrace.firstOrNull { f ->
        f.className.startsWith("com.openbank.products.") && !f.className.startsWith("com.openbank.products.dsl.")
    }
    return SourceLocation(frame?.fileName ?: "unknown", frame?.lineNumber ?: 0)
}
