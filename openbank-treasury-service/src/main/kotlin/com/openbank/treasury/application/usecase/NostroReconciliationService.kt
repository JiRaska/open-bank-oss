// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.treasury.application.port.`in`.NostroReconciliationUseCase
import com.openbank.treasury.application.port.out.DuplicateStatementException
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.NostroStatementRepository
import com.openbank.treasury.application.port.out.StatementNotFoundException
import com.openbank.treasury.application.port.out.StoredStatement
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.NostroMatcher
import com.openbank.treasury.domain.model.NostroReconciliation
import com.openbank.treasury.domain.model.NostroStatement
import com.openbank.treasury.domain.model.TreasuryChart
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Nostro reconciliation (ADR-0315, #10896). [accounts] maps a nostro IBAN to its treasury GL code
 * (config `openbank.treasury.nostro.accounts`); a statement for any other account is refused, and
 * a mapping to a GL code the treasury chart does not know as a nostro is refused at construction.
 */
class NostroReconciliationService(
    private val statements: NostroStatementRepository,
    private val ledger: LedgerReadPort,
    accounts: Map<String, String>,
    private val clock: Clock,
) : NostroReconciliationUseCase {

    private val accounts: Map<String, String> = accounts.mapKeys { it.key.replace(" ", "").uppercase() }

    init {
        val nostroCodes = TreasuryChart.nostroCodes
        this.accounts.forEach { (iban, code) ->
            require(code in nostroCodes) { "nostro account $iban maps to $code, which is not a treasury nostro GL" }
        }
    }

    override suspend fun upload(
        statement: NostroStatement,
        sha256: String,
        idempotencyKey: String,
        actor: Actor,
    ): StoredStatement {
        replayOrConflict(statement, sha256, idempotencyKey)?.let { return it }
        val glCode = requireNotNull(accounts[statement.iban]) { "${statement.iban} is not a configured nostro account" }
        require(TreasuryChart.code(statement.currency, "nostro") == glCode) {
            "statement currency ${statement.currency} does not match nostro GL $glCode"
        }
        val candidate =
            StoredStatement(
                id = Ids.newId(),
                idempotencyKey = idempotencyKey,
                glCode = glCode,
                sha256 = sha256,
                uploadedBy = actor.id,
                uploadedAt = Instant.now(clock),
                statement = statement,
            )
        return try {
            statements.save(candidate)
        } catch (e: DuplicateStatementException) {
            // A concurrent upload passed the same pre-check and committed first; answer as the
            // pre-check would have now (replay or 409), never the constraint violation as a 500.
            replayOrConflict(statement, sha256, idempotencyKey) ?: throw e
        }
    }

    /**
     * The prior upload for this key when it carried the same bytes (idempotent replay); a 409
     * (IllegalStateException) for the key reused on other bytes or for an account + statement id
     * already stored; null when neither exists.
     */
    private suspend fun replayOrConflict(
        statement: NostroStatement,
        sha256: String,
        idempotencyKey: String,
    ): StoredStatement? {
        statements.findByIdempotencyKey(idempotencyKey)?.let { prior ->
            check(prior.sha256 == sha256) { "Idempotency-Key '$idempotencyKey' was used for a different statement" }
            return prior
        }
        statements.findByAccountAndStatementId(statement.iban, statement.statementId)?.let {
            error("statement ${statement.statementId} for ${statement.iban} was already uploaded as ${it.id}")
        }
        return null
    }

    override suspend fun reconcile(statementId: UUID): NostroReconciliation {
        val stored = statements.findById(statementId) ?: throw StatementNotFoundException(statementId)
        val statement = stored.statement
        // A statement may span several booking days: read the ledger over the whole span, and
        // take the opening balance as at the day BEFORE its earliest date.
        // The span is bounded to NostroStatement.MAX_SPAN_DAYS at construction (upload is a 400).
        val from = statement.firstDate
        val to = statement.lastDate
        val lines = ledger.nostroLines(stored.glCode, from, to)
        val comparable = statement.currency == BASE_CURRENCY
        return NostroMatcher.match(
            statement = statement,
            glCode = stored.glCode,
            ledgerLines = lines,
            ledgerOpeningBalance = if (comparable) ledger.glBalance(stored.glCode, from.minusDays(1)) else null,
            ledgerClosingBalance = if (comparable) ledger.glBalance(stored.glCode, to) else null,
            balanceNotStated = if (comparable) null else BALANCE_NOT_STATED,
        )
    }

    companion object {
        /** The ledger's base currency: every balance it exposes is `base_amount` in this currency. */
        const val BASE_CURRENCY = Deal.CZK
        const val BALANCE_NOT_STATED = "ledger exposes only base-currency (CZK) balances for this account"
    }
}
