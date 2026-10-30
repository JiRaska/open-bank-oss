// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.Posting
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.ir.ProductState
import com.openbank.libs.product.replay.BehaviourDiff
import com.openbank.libs.product.replay.DiffOptions
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayAccount
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayPosting
import com.openbank.libs.product.replay.ReplayRange
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0352 §11.4 / phase 5b: the backtest the Studio approval panel shows next to the role approval
 * buttons. Replays a portfolio under a base revision (by default the offering's revision published
 * now) and under the candidate with the shared [ProductBehaviourDiff] — the same engine and the same
 * schema-v1 document `obp diff --behavioural` prints. Stores, signs and approves nothing.
 *
 * The replay is CPU-bound and synchronous, so it runs on [Dispatchers.Default] and is bounded by
 * [MAX_ACCOUNTS], [MAX_POSTINGS_PER_ACCOUNT] and [MAX_ACCOUNT_DAYS] before any evaluation starts.
 */
@ApplicationScoped
class RevisionBehaviourDiffService(private val catalog: GenericCatalogService, private val clock: Clock) {

    private val engine = ProductBehaviourDiff(ProductInterpreter(FinanceProductArithmetic))

    suspend fun diff(
        offeringId: UUID,
        revisionId: UUID,
        baseRevisionId: UUID?,
        portfolio: ReplayPortfolio,
        range: ReplayRange,
        options: DiffOptions,
    ): BehaviourDiff {
        require(portfolio.accounts.size.toLong() * range.days <= MAX_ACCOUNT_DAYS) {
            "portfolio of ${portfolio.accounts.size} accounts over ${range.days} days exceeds $MAX_ACCOUNT_DAYS account-days"
        }
        val candidate = irOf(catalog.findRevision(revisionId).also { requireOffering(it.offeringId, offeringId) })
        val base = irOf(
            if (baseRevisionId != null) {
                catalog.findRevision(baseRevisionId).also { requireOffering(it.offeringId, offeringId) }
            } else {
                catalog.findPublished(offeringId, Instant.now(clock))
            },
        )
        return withContext(Dispatchers.Default) { engine.diff(base, candidate, portfolio, range, options) }
    }

    private fun requireOffering(actual: UUID, expected: UUID) {
        if (actual != expected) throw CatalogNotFoundException("revision does not belong to offering $expected")
    }

    private fun irOf(revision: com.openbank.productcatalog.domain.catalog.ProductRevision): ProductRevisionIr =
        requireNotNull(catalog.parseBehaviour(revision.content)) {
            "revision ${revision.id} carries no product IR (behaviour)"
        }

    companion object {
        const val MAX_ACCOUNTS = 500
        const val MAX_POSTINGS_PER_ACCOUNT = 1000
        const val MAX_ACCOUNT_DAYS = 200_000L
        private const val MAX_INTEGER_DIGITS = 15
        private const val MAX_SCALE = 12
        private val PLAIN_DECIMAL = Regex("^-?[0-9]{1,$MAX_INTEGER_DIGITS}(\\.[0-9]{1,$MAX_SCALE})?$")

        /**
         * The request's portfolio as a plain JSON tree: `{"accounts": [{"id", "state": {"currency",
         * "balance", "accruedInterest", "openedOn"}, "postings": [{"on", "kind", "direction",
         * "amount"}]}]}`. Unknown keys are refused, so a typo is a 400, never a silently ignored field.
         */
        fun parsePortfolio(tree: Any?): ReplayPortfolio {
            val root = obj(tree, "portfolio", setOf("accounts"))
            val accounts = list(root["accounts"], "portfolio.accounts")
            require(accounts.isNotEmpty()) { "portfolio.accounts must not be empty" }
            require(accounts.size <= MAX_ACCOUNTS) { "portfolio.accounts exceeds $MAX_ACCOUNTS" }
            return ReplayPortfolio(accounts.mapIndexed { i, a -> account(a, "portfolio.accounts[$i]") })
        }

        private fun account(tree: Any?, at: String): ReplayAccount {
            val a = obj(tree, at, setOf("id", "state", "postings"))
            val state = obj(a["state"], "$at.state", setOf("currency", "balance", "accruedInterest", "openedOn"))
            val postings = list(a["postings"] ?: emptyList<Any?>(), "$at.postings")
            require(postings.size <= MAX_POSTINGS_PER_ACCOUNT) { "$at.postings exceeds $MAX_POSTINGS_PER_ACCOUNT" }
            return ReplayAccount(
                id = text(a["id"], "$at.id"),
                initialState = ProductState(
                    currency = text(state["currency"], "$at.state.currency"),
                    balance = decimal(state["balance"], "$at.state.balance"),
                    accruedInterest = state["accruedInterest"]?.let { decimal(it, "$at.state.accruedInterest") }
                        ?: BigDecimal.ZERO,
                    openedOn = date(state["openedOn"], "$at.state.openedOn"),
                ),
                postings = postings.mapIndexed { j, p -> posting(p, "$at.postings[$j]") },
            )
        }

        private fun posting(tree: Any?, at: String): ReplayPosting {
            val p = obj(tree, at, setOf("on", "kind", "direction", "amount"))
            return ReplayPosting(
                date(p["on"], "$at.on"),
                Posting(
                    enumOf<EventKind>(p["kind"], "$at.kind"),
                    enumOf<PostingDirection>(p["direction"], "$at.direction"),
                    decimal(p["amount"], "$at.amount"),
                ),
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun obj(tree: Any?, at: String, keys: Set<String>): Map<String, Any?> {
            val map = requireNotNull(tree as? Map<String, Any?>) { "$at must be an object" }
            val unknown = map.keys - keys
            require(unknown.isEmpty()) { "$at has unknown keys ${unknown.sorted()}" }
            return map
        }

        private fun list(tree: Any?, at: String): List<Any?> = requireNotNull(tree as? List<*>) {
            "$at must be an array"
        }

        private fun text(tree: Any?, at: String): String =
            requireNotNull((tree as? String)?.takeIf { it.isNotBlank() }) { "$at must be a non-blank string" }

        /**
         * A money amount: a plain decimal of at most [MAX_INTEGER_DIGITS] integer digits and
         * [MAX_SCALE] decimals. An exponent form (`1E+20`, `1e999999`) is refused, not normalised:
         * it would let one number sidestep every account-day bound on the replay's arithmetic.
         */
        fun decimal(tree: Any?, at: String): BigDecimal {
            val text = when (tree) {
                is String -> tree
                // Bound BEFORE rendering: toPlainString() of 1E+999999 is a million digits.
                is BigDecimal -> tree.takeIf {
                    it.scale() in 0..MAX_SCALE && it.precision() - it.scale() <= MAX_INTEGER_DIGITS
                }?.toPlainString()
                is Number -> tree.toString()
                else -> null
            }
            require(text != null && PLAIN_DECIMAL.matches(text)) {
                "$at must be a plain decimal with at most $MAX_INTEGER_DIGITS integer digits and $MAX_SCALE decimals"
            }
            return BigDecimal(text)
        }

        private fun date(tree: Any?, at: String): LocalDate = (tree as? String)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: throw IllegalArgumentException("$at must be an ISO date")

        private inline fun <reified E : Enum<E>> enumOf(tree: Any?, at: String): E =
            enumValues<E>().firstOrNull { it.name == tree } ?: throw IllegalArgumentException(
                "$at must be one of ${enumValues<E>().joinToString { it.name }}",
            )
    }
}
