// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.replay

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductRevisionIr
import java.math.BigDecimal
import java.util.SortedMap
import java.util.TreeMap

/** Diff knobs. An account whose product outcome moves by at least [materialityThreshold] is listed as crossing it. */
data class DiffOptions(val materialityThreshold: BigDecimal = BigDecimal("1.00")) {
    init {
        require(materialityThreshold.signum() >= 0) { "materialityThreshold must not be negative" }
    }
}

/** A behavioural diff: the canonical JSON tree ([tree]) and its bytes ([canonicalJson]). */
class BehaviourDiff internal constructor(val tree: Map<String, Any?>, val a: ReplayResult, val b: ReplayResult) {
    val canonicalJson: String by lazy { CanonicalJson.write(tree) }

    /** True iff no account and no aggregate bucket differs between the revisions. */
    val identical: Boolean get() = tree["identical"] == true
}

/**
 * Revision-vs-revision behavioural diff (ADR-0352 §11.1 `obp diff --behavioural`, §11.4 the
 * repricing backtest, phase 4): replays one portfolio under two revisions with [ProductReplay] and
 * reports what moved — posted amounts per `ruleId` × accounting class, instruction counts per rule
 * and kind, per account and in aggregate; accounts whose product outcome changed sign or moved by
 * at least the materiality threshold; and money conservation under each revision.
 *
 * Offline and deterministic: the only inputs are the arguments, every collection in the output is
 * ordered, and every amount is a fixed-scale decimal string (`ProductIrCanonical.instructionAmount`,
 * scale 12), so one input gives one byte sequence. The output schema is
 * `product-ir/behaviour-diff.v1.schema.json`; the CLI and the Studio approval panel both render
 * this object rather than computing their own.
 *
 * Lives in `openbank-libs-domain` next to the interpreter rather than in `openbank-libs-testing`
 * (ADR-0352 §5): the Studio approval panel runs it inside the product-catalog service and the
 * benchmark module measures it, and neither may depend on the test-fixture library.
 */
class ProductBehaviourDiff(interpreter: ProductInterpreter) {

    private val replay = ProductReplay(interpreter)

    fun diff(
        revA: ProductRevisionIr,
        revB: ProductRevisionIr,
        portfolio: ReplayPortfolio,
        clockRange: ReplayRange,
        options: DiffOptions = DiffOptions(),
    ): BehaviourDiff {
        // Both runs keep the same history, so a difference is the revisions', never the pruning's.
        val history = ProductReplay.historyWindow(revA, revB)
        val a = replay.run(revA, portfolio, clockRange, historyDays = history)
        val b = replay.run(revB, portfolio, clockRange, historyDays = history)

        val accountRows = ArrayList<Map<String, Any?>>()
        val signChanged = ArrayList<String>()
        val thresholdCrossed = ArrayList<String>()
        val postedA = TreeMap<PostedKey, Movement>()
        val postedB = TreeMap<PostedKey, Movement>()
        val countsA = TreeMap<CountKey, Int>()
        val countsB = TreeMap<CountKey, Int>()
        for ((ra, rb) in a.accounts.zip(b.accounts)) {
            ra.posted.forEach { (k, m) -> postedA.merge(k, m, Movement::plus) }
            rb.posted.forEach { (k, m) -> postedB.merge(k, m, Movement::plus) }
            ra.counts.forEach { (k, n) -> countsA.merge(k, n, Int::plus) }
            rb.counts.forEach { (k, n) -> countsB.merge(k, n, Int::plus) }
            val outcomeDelta = rb.outcome - ra.outcome
            val sign = ra.outcome.signum() != rb.outcome.signum()
            val crossed = outcomeDelta.signum() != 0 && outcomeDelta.abs() >= options.materialityThreshold
            if (sign) signChanged += ra.id
            if (crossed) thresholdCrossed += ra.id
            accountRow(ra, rb, sign, crossed)?.let { accountRows += it }
        }
        val aggregatePosted = postedRows(postedA, postedB)
        val aggregateCounts = countRows(countsA, countsB)
        val outcomeA = a.accounts.fold(BigDecimal.ZERO) { acc, r -> acc + r.outcome }
        val outcomeB = b.accounts.fold(BigDecimal.ZERO) { acc, r -> acc + r.outcome }
        val tree = mapOf(
            "schema" to SCHEMA_ID,
            "revisionA" to revisionRef(revA),
            "revisionB" to revisionRef(revB),
            "range" to mapOf(
                "from" to clockRange.from.toString(),
                "to" to clockRange.to.toString(),
                "zone" to clockRange.zone.id,
                "days" to clockRange.days,
            ),
            "portfolio" to mapOf("accounts" to portfolio.accounts.size, "seed" to portfolio.seed?.toString()),
            "materialityThreshold" to amount(options.materialityThreshold),
            "identical" to (accountRows.isEmpty() && aggregatePosted.isEmpty() && aggregateCounts.isEmpty()),
            "changedAccounts" to accountRows.size,
            "aggregate" to mapOf(
                "outcome" to triple(outcomeA, outcomeB),
                "posted" to aggregatePosted,
                "instructionCounts" to aggregateCounts,
            ),
            "signChanged" to signChanged,
            "thresholdCrossed" to thresholdCrossed,
            "accounts" to accountRows,
            "conservation" to mapOf("a" to conservation(a), "b" to conservation(b)),
        )
        return BehaviourDiff(tree, a, b)
    }

    /** The account's row, or null when nothing about it differs between the revisions. */
    private fun accountRow(ra: AccountReplay, rb: AccountReplay, sign: Boolean, crossed: Boolean): Map<String, Any?>? {
        val posted = postedRows(ra.posted, rb.posted)
        val counts = countRows(ra.counts, rb.counts)
        if (posted.isEmpty() && counts.isEmpty() && ra.rejectedPostings == rb.rejectedPostings) return null
        return mapOf(
            "account" to ra.id,
            "outcome" to triple(ra.outcome, rb.outcome),
            "finalBalance" to triple(ra.finalBalance, rb.finalBalance),
            "rejectedPostings" to mapOf("a" to ra.rejectedPostings, "b" to rb.rejectedPostings),
            "signChanged" to sign,
            "thresholdCrossed" to crossed,
            "posted" to posted,
            "instructionCounts" to counts,
        )
    }

    private fun postedRows(
        a: SortedMap<PostedKey, Movement>,
        b: SortedMap<PostedKey, Movement>,
    ): List<Map<String, Any?>> = (a.keys + b.keys).toSortedSet().mapNotNull { k ->
        val ma = a[k] ?: Movement()
        val mb = b[k] ?: Movement()
        if (ma.sameAs(mb)) {
            null
        } else {
            mapOf(
                "ruleId" to k.ruleId,
                "accountingClass" to k.accountingClass.name,
                "a" to movement(ma),
                "b" to movement(mb),
                "deltaNet" to amount(mb.net - ma.net),
            )
        }
    }

    private fun countRows(a: SortedMap<CountKey, Int>, b: SortedMap<CountKey, Int>): List<Map<String, Any?>> =
        (a.keys + b.keys).toSortedSet().mapNotNull { k ->
            val na = a[k] ?: 0
            val nb = b[k] ?: 0
            if (na ==
                nb
            ) {
                null
            } else {
                mapOf("ruleId" to k.ruleId, "kind" to k.kind, "a" to na, "b" to nb, "delta" to nb - na)
            }
        }

    private fun conservation(r: ReplayResult): Map<String, Any?> = mapOf(
        "conserved" to r.conserved,
        "currencies" to r.conservation.map { (ccy, m) ->
            mapOf("currency" to ccy, "debit" to amount(m.debit), "credit" to amount(m.credit))
        },
    )

    companion object {
        /** The `schema` member of every diff; the JSON Schema's `$id` names the same version. */
        const val SCHEMA_ID: String = "openbank.product-behaviour-diff.v1"

        private fun amount(v: BigDecimal): String = ProductIrCanonical.instructionAmount(v)

        private fun movement(m: Movement): Map<String, Any?> =
            mapOf("debit" to amount(m.debit), "credit" to amount(m.credit), "net" to amount(m.net))

        private fun triple(a: BigDecimal, b: BigDecimal): Map<String, Any?> =
            mapOf("a" to amount(a), "b" to amount(b), "delta" to amount(b - a))

        private fun revisionRef(r: ProductRevisionIr): Map<String, Any?> =
            mapOf("productCode" to r.productCode, "revisionHash" to r.revisionHash, "irVersion" to r.irVersion)
    }
}
