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
import com.openbank.treasury.domain.model.NostroMatcher
import com.openbank.treasury.domain.model.NostroReconciliation
import com.openbank.treasury.domain.model.NostroStatement
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Nostro reconciliation (ADR-0315, #10896). [accounts] maps a nostro IBAN to its treasury GL code
 * (config `openbank.treasury.nostro.accounts`); a statement for any other account is refused, and so
 * is one whose configured GL code is not the ledger's nostro account of the statement currency.
 */
class NostroReconciliationService(
    private val statements: NostroStatementRepository,
    private val ledger: LedgerReadPort,
    accounts: Map<String, String>,
    private val clock: Clock,
) : NostroReconciliationUseCase {

    private val accounts: Map<String, String> = accounts.mapKeys { it.key.replace(" ", "").uppercase() }

    override suspend fun upload(
        statement: NostroStatement,
        sha256: String,
        idempotencyKey: String,
        actor: Actor,
    ): StoredStatement {
        replayOrConflict(statement, sha256, idempotencyKey)?.let { return it }
        val glCode = requireNotNull(accounts[statement.iban]) { "${statement.iban} is not a configured nostro account" }
        // Which GL is the nostro of a currency is the LEDGER's map (ADR-0341 D3), asked at upload time:
        // treasury carries no GL code of its own. This also refuses an IBAN configured onto a GL that
        // is not a nostro at all, which used to be a construction-time check against a literal list.
        val nostro = requireNotNull(ledger.nostroAccounts()[statement.currency]) {
            "the ledger maps no nostro account in ${statement.currency}"
        }
        require(nostro.glCode == glCode) {
            "statement currency ${statement.currency} does not match nostro GL $glCode (the ledger maps ${nostro.glCode})"
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
        // The balances and the lines describe ONE period: opening as at the OPBD balance's date,
        // closing as at the CLBD balance's date, lines booked in between — (openingDate,
        // statementDate]. Reading lines to a later day than the closing balance (or opening a day
        // before the first entry) would turn a quiet day or a later booking into a false difference.
        // Balances are read in the STATEMENT currency from native amounts (#11107), every nostro alike.
        val nostro = requireNotNull(ledger.nostroAccounts().values.firstOrNull { it.glCode == stored.glCode }) {
            "${stored.glCode} is not a nostro account in the ledger's accounting class map"
        }
        val lines = ledger.nostroLines(nostro, statement.firstDate, statement.lastDate)
        val opening = ledger.accountBalance(stored.glCode, statement.currency, statement.openingDate)
        val closing = ledger.accountBalance(stored.glCode, statement.currency, statement.statementDate)
        val stated = opening != null && closing != null
        return NostroMatcher.match(
            statement = statement,
            glCode = stored.glCode,
            ledgerLines = lines,
            ledgerOpeningBalance = if (stated) opening else null,
            ledgerClosingBalance = if (stated) closing else null,
            balanceNotStated = if (stated) null else "ledger does not hold GL account ${stored.glCode}",
        )
    }
}
