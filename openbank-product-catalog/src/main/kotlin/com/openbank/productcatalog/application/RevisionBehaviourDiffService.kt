// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.libs.domain.error.RateLimitExceeded
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.replay.BehaviourDiff
import com.openbank.libs.product.replay.DiffOptions
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayPortfolio
import com.openbank.libs.product.replay.ReplayRange
import com.openbank.productcatalog.domain.catalog.ProductRevision
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * ADR-0352 §11.4 / phase 5b: the backtest the Studio approval panel shows next to the role approval
 * buttons. Replays a portfolio under a base revision (by default the offering's revision published
 * now) and under the candidate with the shared [ProductBehaviourDiff] — the same engine and the same
 * schema-v1 document `obp diff --behavioural` prints. The portfolio is read by the same
 * [ReplayPortfolioJson] the CLI uses. Stores, signs and approves nothing.
 *
 * The replay is CPU-bound, so it is bounded three ways before any evaluation starts: the document
 * ([ReplayPortfolioJson.MAX_ACCOUNTS], its per-account event cap and plain-decimal amounts), the work
 * ([MAX_ACCOUNT_DAYS]), and the concurrency — at most [maxConcurrent] replays run at once on
 * [Dispatchers.Default]; another caller gets a 429 rather than a queue.
 */
@ApplicationScoped
class RevisionBehaviourDiffService(
    private val catalog: GenericCatalogService,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.catalog.behaviour-diff.max-concurrent", defaultValue = "2")
    private val maxConcurrent: Int,
) {

    private val engine = ProductBehaviourDiff(ProductInterpreter(FinanceProductArithmetic))
    private val slots = ConcurrencyLimit(maxConcurrent)

    suspend fun diff(
        offeringId: UUID,
        revisionId: UUID,
        baseRevisionId: UUID?,
        portfolio: ReplayPortfolio,
        range: ReplayRange,
        options: DiffOptions,
        requester: BacktestRequester,
    ): BehaviourDiff {
        require(portfolio.accounts.size.toLong() * range.days <= MAX_ACCOUNT_DAYS) {
            "portfolio of ${portfolio.accounts.size} accounts over ${range.days} days exceeds $MAX_ACCOUNT_DAYS account-days"
        }
        return slots.run {
            val candidateRevision = catalog.findRevision(revisionId).also { requireOffering(it.offeringId, offeringId) }
            val candidate = irOf(candidateRevision)
            val base = irOf(
                if (baseRevisionId != null) {
                    catalog.findRevision(baseRevisionId).also { requireOffering(it.offeringId, offeringId) }
                } else {
                    catalog.findPublished(offeringId, Instant.now(clock))
                },
            )
            val diff = withContext(Dispatchers.Default) { engine.diff(base, candidate, portfolio, range, options) }
            // Evidence for the approval gate (ADR-0352 §7): only a VALID diff counts as having seen the change.
            if (diff.valid) {
                catalog.recordBacktest(
                    candidateRevision,
                    requester,
                    base.revisionHash,
                    candidate.revisionHash,
                    diff.canonicalJson,
                )
            }
            diff
        }
    }

    private fun requireOffering(actual: UUID, expected: UUID) {
        if (actual != expected) throw CatalogNotFoundException("revision does not belong to offering $expected")
    }

    private fun irOf(revision: ProductRevision): ProductRevisionIr =
        requireNotNull(catalog.parseBehaviour(revision.content)) {
            "revision ${revision.id} carries no product IR (behaviour)"
        }

    companion object {
        const val MAX_ACCOUNT_DAYS = 200_000L
    }
}

/**
 * A non-blocking admission limit: [run] takes a slot or refuses at once with [RateLimitExceeded]
 * (429). A refusal, not a queue — a queue would let a burst of backtests hold requests open while
 * the CPU they wait for is the thing that is exhausted.
 */
class ConcurrencyLimit(permits: Int) {
    private val semaphore = Semaphore(permits.coerceAtLeast(0))

    suspend fun <T> run(block: suspend () -> T): T {
        if (!semaphore.tryAcquire()) {
            throw RateLimitExceeded("Too many behavioural diffs running; retry shortly", retryAfter = RETRY_AFTER)
        }
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    private companion object {
        val RETRY_AFTER: Duration = Duration.ofSeconds(5)
    }
}
