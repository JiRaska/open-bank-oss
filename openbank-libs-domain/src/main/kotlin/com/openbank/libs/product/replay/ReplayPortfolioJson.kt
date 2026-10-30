// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.replay

import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The ONE reader of a behavioural-diff portfolio document (ADR-0352 §11.4, phase 5b), shared by
 * `obp diff --behavioural` and the catalog's approval-panel backtest so both replay the same accounts
 * from the same bytes.
 *
 * ```json
 * {"zone": "UTC", "accounts": [{"id": "a1",
 *   "state": {"currency": "EUR", "balance": "500.00", "accruedInterest": "0", "openedOn": "2026-01-01"},
 *   "postings": [{"on": "2026-01-10", "kind": "WITHDRAWAL", "direction": "DEBIT", "amount": "150.00"}],
 *   "steps": [{"at": "2026-01-10T10:00:00Z", "hook": "prePosting", "posting": {"kind", "direction", "amount"}}]}]}
 * ```
 *
 * An account carries `postings`, golden-scenario `steps` (the grammar of `openbank-products/portfolios`
 * and the golden fixtures), or both. A step with a `posting` becomes a customer movement on its calendar
 * day in `zone`; a step without one (a `scheduled` tick) is skipped, because the replay runs the schedules
 * each revision registers in `onOpen` — that is what a revision-vs-revision comparison is for.
 *
 * Strict: unknown keys are refused, and every amount is a plain decimal of at most [MAX_INTEGER_DIGITS]
 * integer digits and [MAX_SCALE] decimals — an exponent form (`1E+20`) is refused before it is rendered,
 * since one such number would sidestep any bound on the replay's arithmetic. Errors are
 * [IllegalArgumentException]s naming the JSON path.
 */
object ReplayPortfolioJson {

    const val MAX_ACCOUNTS: Int = 500
    const val MAX_EVENTS_PER_ACCOUNT: Int = 1000
    private const val MAX_INTEGER_DIGITS = 15
    private const val MAX_SCALE = 12
    private val PLAIN_DECIMAL = Regex("^-?[0-9]{1,$MAX_INTEGER_DIGITS}(\\.[0-9]{1,$MAX_SCALE})?$")
    private val STATE_KEYS = setOf(
        "currency",
        "balance",
        "accruedInterest",
        "openedOn",
        "segment",
        "monthlyTurnover",
        "aggregatePocketBalance",
    )
    private val STEP_KEYS = setOf("at", "hook", "tag", "posting", "expected", "parameter", "newValue")

    /**
     * A parsed portfolio and the calendar it implies: earliest `openedOn` through the latest day any
     * posting or step (scheduled ticks included — they state the horizon a fixture observes) falls on.
     */
    data class Parsed(val portfolio: ReplayPortfolio, val zone: ZoneId, val firstDay: LocalDate, val lastDay: LocalDate)

    fun parse(tree: Any?, defaultZone: ZoneId = ZoneId.of("UTC")): Parsed {
        val root = obj(tree, "portfolio", setOf("zone", "accounts"))
        val zone = (root["zone"] as? String)?.let {
            runCatching { ZoneId.of(it) }.getOrNull()
                ?: throw IllegalArgumentException("portfolio.zone '$it' is not a zone id")
        } ?: defaultZone
        val accounts = list(root["accounts"], "portfolio.accounts")
        require(accounts.isNotEmpty()) { "portfolio.accounts must not be empty" }
        require(accounts.size <= MAX_ACCOUNTS) { "portfolio.accounts exceeds $MAX_ACCOUNTS" }
        val parsed = accounts.mapIndexed { i, a -> account(a, "portfolio.accounts[$i]", zone) }
        val first = parsed.minOf { it.first.initialState.openedOn }
        val last = parsed.mapNotNull { it.second }.maxOrNull()?.let { maxOf(it, first) } ?: first
        return Parsed(ReplayPortfolio(parsed.map { it.first }), zone, first, last)
    }

    /** A money amount in the bounded plain-decimal form; also the parser for a request's threshold. */
    fun amount(tree: Any?, at: String): BigDecimal {
        val text = when (tree) {
            is String -> tree
            // Bound BEFORE rendering: toPlainString() of 1E+999999 is a million digits.
            is BigDecimal -> tree.takeIf {
                it.scale() in 0..MAX_SCALE && it.precision() - it.scale() <= MAX_INTEGER_DIGITS
            }?.toPlainString()
            is Int, is Long -> tree.toString()
            else -> null
        }
        require(text != null && PLAIN_DECIMAL.matches(text)) {
            "$at must be a plain decimal with at most $MAX_INTEGER_DIGITS integer digits and $MAX_SCALE decimals"
        }
        return BigDecimal(text)
    }

    /** The account and the latest day any of its postings or steps falls on. */
    private fun account(tree: Any?, at: String, zone: ZoneId): Pair<ReplayAccount, LocalDate?> {
        val a = obj(tree, at, setOf("id", "state", "postings", "steps"))
        val state = obj(a["state"], "$at.state", STATE_KEYS)
        val postings = list(a["postings"] ?: emptyList<Any?>(), "$at.postings")
        val steps = list(a["steps"] ?: emptyList<Any?>(), "$at.steps")
        require(postings.size + steps.size <= MAX_EVENTS_PER_ACCOUNT) { "$at exceeds $MAX_EVENTS_PER_ACCOUNT events" }
        val parsedSteps = steps.mapIndexed { j, s -> step(s, "$at.steps[$j]", zone) }
        val movements = postings.mapIndexed { j, p ->
            val posting = obj(p, "$at.postings[$j]", setOf("on", "kind", "direction", "amount"))
            ReplayPosting(date(posting["on"], "$at.postings[$j].on"), movement(posting, "$at.postings[$j]"))
        } + parsedSteps.mapNotNull { it.second }
        val lastDay = (movements.map { it.on } + parsedSteps.map { it.first }).maxOrNull()
        return ReplayAccount(
            id = text(a["id"], "$at.id"),
            initialState = ProductState(
                currency = text(state["currency"], "$at.state.currency"),
                balance = amount(state["balance"], "$at.state.balance"),
                accruedInterest =
                state["accruedInterest"]?.let { amount(it, "$at.state.accruedInterest") } ?: BigDecimal.ZERO,
                openedOn = date(state["openedOn"], "$at.state.openedOn"),
                segment = state["segment"]?.let { text(it, "$at.state.segment") },
                monthlyTurnover = state["monthlyTurnover"]?.let { amount(it, "$at.state.monthlyTurnover") },
                aggregatePocketBalance = state["aggregatePocketBalance"]?.let {
                    amount(it, "$at.state.aggregatePocketBalance")
                },
            ),
            // Stable sort: same-day movements keep their document order.
            postings = movements.sortedBy { it.on },
        ) to lastDay
    }

    /** The step's calendar day, and its customer movement when it carries a `posting`. */
    private fun step(tree: Any?, at: String, zone: ZoneId): Pair<LocalDate, ReplayPosting?> {
        val s = obj(tree, at, STEP_KEYS)
        val instant = (s["at"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: throw IllegalArgumentException("$at.at must be an ISO instant")
        val day = instant.atZone(zone).toLocalDate()
        val posting = s["posting"]?.let {
            ReplayPosting(day, movement(obj(it, "$at.posting", setOf("kind", "direction", "amount")), "$at.posting"))
        }
        return day to posting
    }

    private fun movement(p: Map<String, Any?>, at: String) = Posting(
        enumOf<EventKind>(p["kind"], "$at.kind"),
        enumOf<PostingDirection>(p["direction"], "$at.direction"),
        amount(p["amount"], "$at.amount"),
    )

    @Suppress("UNCHECKED_CAST")
    private fun obj(tree: Any?, at: String, keys: Set<String>): Map<String, Any?> {
        val map = requireNotNull(tree as? Map<String, Any?>) { "$at must be an object" }
        val unknown = map.keys - keys
        require(unknown.isEmpty()) { "$at has unknown keys ${unknown.sorted()}" }
        return map
    }

    private fun list(tree: Any?, at: String): List<Any?> = requireNotNull(tree as? List<*>) { "$at must be an array" }

    private fun text(tree: Any?, at: String): String =
        requireNotNull((tree as? String)?.takeIf { it.isNotBlank() }) { "$at must be a non-blank string" }

    private fun date(tree: Any?, at: String): LocalDate = try {
        LocalDate.parse(tree as? String ?: throw IllegalArgumentException("$at must be an ISO date"))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("$at must be an ISO date", e)
    }

    private inline fun <reified E : Enum<E>> enumOf(tree: Any?, at: String): E =
        enumValues<E>().firstOrNull { it.name == tree }
            ?: throw IllegalArgumentException("$at must be one of ${enumValues<E>().joinToString { it.name }}")
}
