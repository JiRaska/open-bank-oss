// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.libs.domain.error.RateLimitExceeded
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.product.replay.BehaviourDiff
import com.openbank.libs.product.replay.BehaviourDiffInputs
import com.openbank.libs.product.replay.ProductBehaviourDiff
import com.openbank.libs.product.replay.ReplayPortfolioJson
import com.openbank.productcatalog.application.port.out.ApprovalPortfolioSource
import com.openbank.productcatalog.domain.catalog.ProductRevision
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * ADR-0352 §11.4 / phase 5b: the backtest the Studio approval panel shows next to the role approval
 * buttons. Replays a portfolio under a base revision (by default the offering's revision published
 * now) and under the candidate with the shared [ProductBehaviourDiff] — the same engine and the same
 * schema-v1 document `obp diff --behavioural` prints, read by the same [ReplayPortfolioJson] and
 * resolved by the same [BehaviourDiffInputs].
 *
 * Without a portfolio it runs over the SERVER-DEFINED golden portfolio of the OFFERING
 * ([ApprovalPortfolioSource], keyed by the offering's code, never by the author-editable IR, and with no
 * fallback), and only that run — valid, and by a verified JWT principal — is recorded
 * as approval evidence (ADR-0352 §7). A portfolio the caller supplies may still be run to explore, but
 * the caller chose what to look at, so it unlocks nothing.
 *
 * The replay is CPU-bound, so it is bounded three ways before any evaluation starts: the document
 * ([ReplayPortfolioJson.MAX_ACCOUNTS], its per-account event cap and plain-decimal amounts), the work
 * ([MAX_ACCOUNT_DAYS]), and the concurrency — at most [maxConcurrent] replays run at once on
 * [Dispatchers.Default]; another caller gets a 429 rather than a queue.
 */
@ApplicationScoped
class RevisionBehaviourDiffService(
    private val catalog: GenericCatalogService,
    private val portfolios: ApprovalPortfolioSource,
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
        request: BacktestRequest,
        requester: ApproverIdentity?,
    ): BacktestOutcome = slots.run {
        val candidateRevision = catalog.findRevision(revisionId).also { requireOffering(it.offeringId, offeringId) }
        // The offering, not the author's IR, decides which product this is (ADR-0352 §7): re-checked here
        // so even a stored row that predates the write-time check cannot pick another product's portfolio.
        val offering = catalog.findOffering(offeringId)
        val candidate = catalog.requireIrBelongsTo(offering, candidateRevision.content)
            ?: throw IllegalArgumentException("revision ${candidateRevision.id} carries no product IR (behaviour)")
        val base = irOf(
            if (baseRevisionId != null) {
                catalog.findRevision(baseRevisionId).also { requireOffering(it.offeringId, offeringId) }
            } else {
                catalog.findPublished(offeringId, Instant.now(clock))
            },
        )
        val golden = if (request.portfolio == null) {
            portfolios.find(offering.code) ?: throw CatalogConflictException(
                "no server-defined approval portfolio for offering ${offering.code}; it cannot be approved",
            )
        } else {
            null
        }
        val parsed = ReplayPortfolioJson.parse(golden?.tree ?: request.portfolio, request.defaultZone)
        // A schema without a `currency` attribute gave the write nothing to check the IR currency with;
        // the golden portfolio's accounts are the offering's currency, so an IR in another one is refused.
        if (golden != null && parsed.portfolio.accounts.any { it.initialState.currency != candidate.currency }) {
            throw CatalogConflictException(
                "product IR currency ${candidate.currency} is not the currency of offering ${offering.code}'s " +
                    "golden portfolio ${golden.name}",
            )
        }
        val range = BehaviourDiffInputs.range(parsed, request.from, request.to)
        val options = BehaviourDiffInputs.options(request.threshold)
        require(parsed.portfolio.accounts.size.toLong() * range.days <= MAX_ACCOUNT_DAYS) {
            "portfolio of ${parsed.portfolio.accounts.size} accounts over ${range.days} days exceeds " +
                "$MAX_ACCOUNT_DAYS account-days"
        }
        val diff = withContext(Dispatchers.Default) { engine.diff(base, candidate, parsed.portfolio, range, options) }
        // Approval evidence (ADR-0352 §7): the golden portfolio, a valid diff, a verified JWT principal.
        val evidence = golden != null && diff.valid && requester != null
        if (evidence) {
            catalog.recordBacktest(
                candidateRevision,
                BacktestEvidence(
                    approver = requester!!,
                    portfolioName = golden!!.name,
                    portfolioSha256 = golden.sha256,
                    identical = diff.identical,
                    changedAccounts = (diff.tree["changedAccounts"] as Number).toInt(),
                    baseRevisionHash = base.revisionHash,
                    candidateRevisionHash = candidate.revisionHash,
                    canonicalDiff = diff.canonicalJson,
                ),
            )
        }
        BacktestOutcome(diff, evidence, golden?.name)
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

/** A backtest request: no [portfolio] means the product's server-defined golden one. */
data class BacktestRequest(
    val portfolio: Any?,
    val defaultZone: ZoneId,
    val from: LocalDate?,
    val to: LocalDate?,
    val threshold: String?,
)

/** [approvalEvidence]: the run was recorded as evidence that unlocks approval; [goldenPortfolio] names it. */
class BacktestOutcome(val diff: BehaviourDiff, val approvalEvidence: Boolean, val goldenPortfolio: String?)

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
