// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import com.openbank.libs.product.ir.AccountEvent
import com.openbank.libs.product.ir.BalanceSample
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Hook
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductEvent
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Property generators for ADR-0352 (phase 1 subset; phase 6 builds the formal properties on them).
 * A revision is generated as an IR document tree and then parsed, so every value is one the
 * validator accepted — a generator that bypasses the parser would prove things about revisions
 * that cannot exist.
 */
object ProductArb {

    private val rate: Arb<String> = Arb.bigDecimal(BigDecimal("0.0000"), BigDecimal("0.2000")).map { it.setScale(4, RoundingMode.HALF_UP).toPlainString() }
    private val amount: Arb<String> = Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("1000000.00")).map { it.setScale(2, RoundingMode.HALF_UP).toPlainString() }
    private val dayCount: Arb<String> = Arb.element("ACT_365_FIXED", "ACT_360", "ACT_ACT", "THIRTY_360_BOND_BASIS")
    private val cadence: Arb<String> = Arb.element("DAILY", "MONTHLY", "QUARTERLY", "ANNUALLY")

    private val predicate: Arb<Map<String, Any?>> = arbitrary { rs ->
        val days = Arb.int(1, 400).bind()
        when (Arb.int(0, 6).bind()) {
            0 -> mapOf("type" to "noEvents", "kind" to Arb.element(EventKind.entries).bind().name, "window" to mapOf("days" to days))
            1 -> mapOf("type" to "countEvents", "kind" to Arb.element(EventKind.entries).bind().name, "window" to mapOf("days" to days), "operator" to Arb.element("GT", "GTE", "LT", "LTE", "EQ", "NEQ").bind(), "count" to Arb.int(0, 5).bind())
            2 -> mapOf("type" to "sumEvents", "kind" to Arb.element(EventKind.entries).bind().name, "window" to mapOf("days" to days), "operator" to Arb.element("GT", "LT").bind(), "amount" to amount.bind())
            3 -> mapOf("type" to "balanceMin", "window" to mapOf("days" to days), "operator" to Arb.element("GTE", "LT").bind(), "amount" to amount.bind())
            4 -> mapOf("type" to "comparison", "attribute" to "BALANCE", "operator" to Arb.element("GT", "GTE", "LT", "LTE").bind(), "threshold" to amount.bind())
            5 -> mapOf("type" to "comparison", "attribute" to "SEGMENT", "operator" to Arb.element("EQ", "NEQ").bind(), "textValue" to Arb.element("VIP", "RETAIL", "STUDENT").bind())
            else -> mapOf("type" to "not", "operand" to mapOf("type" to "noEvents", "kind" to "WITHDRAWAL", "window" to mapOf("days" to days)))
        }
    }

    private val tiers: Arb<List<Map<String, Any?>>> = arbitrary {
        val bounds = Arb.list(Arb.int(1, 100_000), 1..4).bind().distinct().sorted()
        val froms = listOf(0) + bounds
        froms.mapIndexed { i, from ->
            val to = froms.getOrNull(i + 1)
            buildMap<String, Any?> {
                put("from", "$from.00")
                if (to != null) put("to", "$to.00")
                put("rate", rate.bind())
            }
        }
    }

    /** A valid IR v1 document tree: fixed or tiered interest, optional bonus, 0–3 fees, optional term deposit. */
    val revisionTree: Arb<Map<String, Any?>> = arbitrary {
        val tiered = Arb.boolean().bind()
        val withBonus = Arb.boolean().bind()
        val feeCount = Arb.int(0, 3).bind()
        val term = Arb.boolean().bind()
        val interest = buildMap<String, Any?> {
            put("ruleId", "interest.base")
            put("rateType", if (tiered) "TIERED" else "FIXED")
            if (tiered) put("tiers", tiers.bind()) else put("rate", rate.bind())
            put("dayCount", dayCount.bind())
            put("accrualCadence", "DAILY")
            put("capitalisationCadence", if (term) "AT_MATURITY" else cadence.bind())
            put("accrualRounding", "INTEREST_ACCRUAL")
            put("postingRounding", "LEDGER_POSTING")
            if (withBonus) put("bonus", mapOf("ruleId" to "interest.bonus", "rate" to rate.bind(), "predicate" to predicate.bind()))
        }
        val fees = (0 until feeCount).map { i ->
            buildMap<String, Any?> {
                put("ruleId", "fee.f$i")
                put("feeCode", "F$i")
                put("amount", Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("50.00")).bind().setScale(2, RoundingMode.HALF_UP).toPlainString())
                put("cadence", cadence.bind())
                put("accountingClass", "FEE_INCOME")
                if (Arb.boolean().bind()) put("waiver", predicate.bind())
            }
        }
        val onOpen = ArrayList<Map<String, Any?>>()
        val scheduled = ArrayList<Map<String, Any?>>()
        onOpen += mapOf("ruleId" to "open.accrual", "action" to mapOf("type" to "SCHEDULE", "hook" to "scheduled", "tag" to "ACCRUAL", "cadence" to "DAILY"))
        scheduled += mapOf("ruleId" to "scheduled.accrue", "trigger" to "ACCRUAL", "action" to mapOf("type" to "ACCRUE_INTEREST"))
        if (term) {
            onOpen += mapOf("ruleId" to "open.maturity", "action" to mapOf("type" to "SCHEDULE", "hook" to "scheduled", "tag" to "MATURITY", "cadence" to "AT_MATURITY"))
            scheduled += mapOf("ruleId" to "scheduled.mature", "trigger" to "MATURITY", "action" to mapOf("type" to "MATURE"))
        } else {
            onOpen += mapOf("ruleId" to "open.capitalisation", "action" to mapOf("type" to "SCHEDULE", "hook" to "scheduled", "tag" to "CAPITALISATION", "cadence" to interest["capitalisationCadence"]))
            scheduled += mapOf("ruleId" to "scheduled.capitalise", "trigger" to "CAPITALISATION", "action" to mapOf("type" to "CAPITALISE_INTEREST"))
        }
        fees.forEachIndexed { i, f ->
            onOpen += mapOf("ruleId" to "open.fee$i", "action" to mapOf("type" to "SCHEDULE", "hook" to "scheduled", "tag" to "FEE_$i", "cadence" to f["cadence"]))
            scheduled += mapOf("ruleId" to "scheduled.fee$i", "trigger" to "FEE_$i", "action" to mapOf("type" to "CHARGE_FEE", "feeRuleId" to f["ruleId"]))
        }
        val prePosting = ArrayList<Map<String, Any?>>()
        if (Arb.boolean().bind()) {
            prePosting += mapOf(
                "ruleId" to "posting.no-overdraft",
                "when" to mapOf("type" to "postingComparison", "attribute" to "PROJECTED_BALANCE", "operator" to "LT", "threshold" to "0.00"),
                "action" to mapOf("type" to "REJECT", "reasonCode" to "INSUFFICIENT_FUNDS"),
            )
        }
        if (term) prePosting += mapOf("ruleId" to "posting.early", "action" to mapOf("type" to "EARLY_WITHDRAWAL_PENALTY"))
        buildMap<String, Any?> {
            put("irVersion", 1)
            put("productCode", "ARB-" + Arb.int(1, 999).bind())
            put("currency", Arb.element("EUR", "CZK", "USD").bind())
            put("interest", interest)
            put("fees", fees)
            if (term) {
                put(
                    "termDeposit",
                    buildMap<String, Any?> {
                        put("ruleId", "term.main")
                        put("termMonths", Arb.int(1, 60).bind())
                        put("maturityAction", Arb.element("CAPITALISE", "PAY_OUT", "ROLL_OVER").bind())
                        if (Arb.boolean().bind()) {
                            put("earlyWithdrawal", mapOf("ruleId" to "term.early", "kind" to "RATE", "rate" to rate.bind()))
                        } else {
                            put("earlyWithdrawal", mapOf("ruleId" to "term.early", "kind" to "FORFEITED_INTEREST_FRACTION", "fraction" to Arb.element("0.25", "0.5", "1").bind()))
                        }
                    },
                )
            }
            put("hooks", buildMap<String, Any?> { put("onOpen", onOpen); put("scheduled", scheduled); if (prePosting.isNotEmpty()) put("prePosting", prePosting) })
        }
    }

    /** A validated revision. */
    val revision: Arb<ProductRevisionIr> = revisionTree.map { ProductIrParser.fromTree(it) }

    private val day: Arb<LocalDate> = Arb.int(0, 1500).map { LocalDate.of(2024, 1, 1).plusDays(it.toLong()) }

    /** A state consistent with [currency]; balances non-negative, a few events and samples around [today]. */
    fun state(currency: String, today: LocalDate): Arb<ProductState> = arbitrary {
        val balance = Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("500000.00")).bind().setScale(2, RoundingMode.HALF_UP)
        ProductState(
            currency = currency,
            balance = balance,
            accruedInterest = Arb.bigDecimal(BigDecimal("0.000000"), BigDecimal("5000.000000")).bind().setScale(6, RoundingMode.HALF_UP),
            openedOn = today.minusDays(Arb.int(0, 800).bind().toLong()),
            segment = Arb.element("VIP", "RETAIL", "STUDENT").orNull(0.3).bind(),
            monthlyTurnover = Arb.bigDecimal(BigDecimal("0"), BigDecimal("100000")).orNull(0.3).bind()?.setScale(2, RoundingMode.HALF_UP),
            events = Arb.list(
                arbitrary { AccountEvent(Arb.element(EventKind.entries).bind(), today.minusDays(Arb.int(0, 60).bind().toLong()), Arb.bigDecimal(BigDecimal("0.01"), BigDecimal("9999.00")).bind().setScale(2, RoundingMode.HALF_UP)) },
                0..6,
            ).bind(),
            balanceSamples = Arb.list(
                arbitrary { BalanceSample(today.minusDays(Arb.int(0, 60).bind().toLong()), Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("500000.00")).bind().setScale(2, RoundingMode.HALF_UP)) },
                0..6,
            ).bind(),
        )
    }

    val today: Arb<LocalDate> = day

    /** An event for one of the hooks the generated revisions declare rules on. */
    fun event(revision: ProductRevisionIr): Arb<ProductEvent> = arbitrary {
        val tags = revision.hooks[Hook.SCHEDULED].orEmpty().mapNotNull { it.trigger }.distinct()
        when (Arb.int(0, 3).bind()) {
            0 -> ProductEvent(Hook.ON_OPEN)
            1, 2 -> ProductEvent(Hook.SCHEDULED, tag = Arb.element(tags).bind())
            else -> ProductEvent(
                Hook.PRE_POSTING,
                posting = Posting(
                    Arb.element(EventKind.WITHDRAWAL, EventKind.DEPOSIT, EventKind.CARD_PAYMENT).bind(),
                    Arb.element(PostingDirection.entries).bind(),
                    Arb.bigDecimal(BigDecimal("0.01"), BigDecimal("100000.00")).bind().setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }
    }
}
