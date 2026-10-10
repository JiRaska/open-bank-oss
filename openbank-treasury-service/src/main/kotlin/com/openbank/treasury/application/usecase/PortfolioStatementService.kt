// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.treasury.application.port.`in`.PortfolioStatementUseCase
import com.openbank.treasury.application.port.out.DuplicatePortfolioStatementException
import com.openbank.treasury.application.port.out.PortfolioSnapshotMissingException
import com.openbank.treasury.application.port.out.PortfolioStatementRepository
import com.openbank.treasury.application.port.out.StoredPortfolioStatement
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.CfiClassMapping
import com.openbank.treasury.domain.model.CustodyStatement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Portfolio statements of holdings (ADR-0337 amendment). One deployment serves ONE legal [entity]
 * (the `treasury-pension-co` instance, ADR-0337 D5), reading ONE custodian [safekeepingAccounts]
 * set; a statement for any other account is refused, never filed under a guess.
 *
 * [entity] is null on a deployment that keeps no portfolio (the bank's own treasury): every upload
 * is then refused and every read is a 409 — there is no snapshot, and nothing pretends otherwise.
 */
class PortfolioStatementService(
    private val statements: PortfolioStatementRepository,
    private val entity: String?,
    safekeepingAccounts: Collection<String>,
    private val classes: CfiClassMapping,
    private val clock: Clock,
) : PortfolioStatementUseCase {

    private val accounts: Set<String> = safekeepingAccounts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    init {
        require(entity == null || entity.isNotBlank()) { "the portfolio entity, when configured, must not be blank" }
    }

    override suspend fun upload(
        statement: CustodyStatement,
        sha256: String,
        idempotencyKey: String,
        actor: Actor,
    ): StoredPortfolioStatement {
        val owner = requireNotNull(entity) { "this deployment keeps no portfolio (openbank.treasury.portfolio.entity)" }
        require(statement.safekeepingAccount in accounts) {
            "safekeeping account ${statement.safekeepingAccount} is not configured for $owner"
        }
        replay(owner, statement.statementDate, sha256, idempotencyKey)?.let { return it }
        val currency = currencyOf(statement)
        val snapshot = classes.classify(owner, statement, currency)
        val prior = statements.current(owner, statement.statementDate)
        val candidate = StoredPortfolioStatement(
            id = Ids.newId(),
            idempotencyKey = idempotencyKey,
            version = (prior?.version ?: 0) + 1,
            supersedes = prior?.id,
            supersededBy = null,
            supersededAt = null,
            sha256 = sha256,
            uploadedBy = actor.id,
            uploadedAt = Instant.now(clock),
            snapshot = snapshot,
        )
        return try {
            statements.save(candidate)
        } catch (e: DuplicatePortfolioStatementException) {
            // A concurrent upload or correction committed first. Same bytes: that IS this upload.
            // Otherwise the caller corrected against a version that is no longer current: 409.
            replay(owner, statement.statementDate, sha256, idempotencyKey)
                ?: throw IllegalStateException(
                    "the statement for ${statement.statementDate} changed while this one was stored; re-read and retry",
                    e,
                )
        }
    }

    /**
     * The stored version this upload already is: the same key with the same bytes, or the same
     * bytes as the date's current version under any key. The key on other bytes is a 409.
     */
    private suspend fun replay(owner: String, date: LocalDate, sha256: String, key: String): StoredPortfolioStatement? {
        statements.findByIdempotencyKey(key)?.let { prior ->
            check(prior.sha256 == sha256) { "Idempotency-Key was used for a different statement" }
            return prior
        }
        val current = statements.current(owner, date)?.takeIf { it.sha256 == sha256 } ?: return null
        val bound = statements.bindIdempotencyKey(key, current.id)
        check(bound.sha256 == sha256) { "Idempotency-Key was used for a different statement" }
        return bound
    }

    /**
     * Every holding must state a valuation in one currency. A holding with no valuation cannot be
     * reported at value, and a total over two currencies is no figure at all.
     */
    private fun currencyOf(statement: CustodyStatement): String {
        val currencies = statement.holdings.map { it.valuationCurrency }.toSet()
        require(currencies.size <= 1) {
            "statement for ${statement.statementDate} values holdings in $currencies; one base currency is required"
        }
        return currencies.singleOrNull() ?: EMPTY_PORTFOLIO_CURRENCY
    }

    override suspend fun periodEnd(date: LocalDate): StoredPortfolioStatement {
        val owner = entity ?: throw PortfolioSnapshotMissingException("(no portfolio entity)", date)
        return statements.current(owner, date) ?: throw PortfolioSnapshotMissingException(owner, date)
    }

    override suspend fun versions(date: LocalDate): List<StoredPortfolioStatement> =
        entity?.let { statements.versions(it, date) }.orEmpty()

    private companion object {
        /** An empty statement states no currency; the company reports in CZK (ČNB returns). */
        const val EMPTY_PORTFOLIO_CURRENCY = "CZK"
    }
}
