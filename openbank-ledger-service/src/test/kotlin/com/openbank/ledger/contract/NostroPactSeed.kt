// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.contract

import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Provider-state seed for treasury-service's nostro-reconciliation reads (ADR-0315 D5, #10896,
 * #11107), shared by [LedgerPactProviderVerificationTest] (`@PactFolder`) and
 * [LedgerPactBrokerProviderVerificationTest] (broker twin) so the two can never seed different
 * data for the same state name.
 *
 * Direct JDBC against `journal_entries`/`journal_lines` (V1's schema: `side` is a single 'D'/'C'
 * char, the table is partitioned by `entry_date`): a `LedgerUseCase.postJournal` call needs a
 * Vert.x context a bare `@State` thread does not carry. Idempotent (`on conflict do nothing`).
 */
object NostroPactSeed {
    const val STATEMENT_DATE = "2026-03-15"

    /**
     * A balanced two-line CZK journal on [STATEMENT_DATE], Dr 1500 (MM placement) / Cr 1001 (CZK
     * nostro, the fixed id V29__treasury_money_market_accounts.sql seeds) — the shape treasury's own
     * SETTLED posting for an MM_PLACEMENT deal produces (`Postings.kt`).
     */
    fun seedCzkNostroLine(dataSource: DataSource) = dataSource.connection.use { c ->
        journal(c, CZK_JOURNAL_ID, CZK_TRANSACTION_ID, "MM placement settlement")
        line(c, CZK_NOSTRO_LINE_ID, CZK_JOURNAL_ID, NOSTRO_CZK_GL_ID, "C", CZK_AMOUNT, "CZK", CZK_AMOUNT, 1)
        line(c, CZK_PLACEMENT_LINE_ID, CZK_JOURNAL_ID, PLACEMENT_CZK_GL_ID, "D", CZK_AMOUNT, "CZK", CZK_AMOUNT, 2)
    }

    /**
     * A balanced two-line EUR journal on [STATEMENT_DATE], Dr 1002 (EUR nostro) / Cr 1501 (EUR MM
     * placement), native amount EUR 10,000.00 with a CZK `base_amount` that differs — so a balance
     * read that summed `base_amount` instead of `amount` would answer 251,000 and fail the pact.
     */
    fun seedEurNostroLine(dataSource: DataSource) = dataSource.connection.use { c ->
        journal(c, EUR_JOURNAL_ID, EUR_TRANSACTION_ID, "EUR MM maturity")
        line(c, EUR_NOSTRO_LINE_ID, EUR_JOURNAL_ID, NOSTRO_EUR_GL_ID, "D", EUR_AMOUNT, "EUR", EUR_BASE_AMOUNT, 1)
        line(c, EUR_PLACEMENT_LINE_ID, EUR_JOURNAL_ID, PLACEMENT_EUR_GL_ID, "C", EUR_AMOUNT, "EUR", EUR_BASE_AMOUNT, 2)
    }

    private fun journal(c: Connection, id: UUID, transactionId: UUID, description: String) {
        c.prepareStatement(
            """insert into journal_entries (id, transaction_id, entry_date, value_date, description, status, created_by)
               values (?, ?, ?, ?, ?, 'POSTED', ?)
               on conflict (id, entry_date) do nothing""",
        ).use { s ->
            s.setObject(1, id)
            s.setObject(2, transactionId)
            s.setObject(3, java.sql.Date.valueOf(STATEMENT_DATE))
            s.setObject(4, java.sql.Date.valueOf(STATEMENT_DATE))
            s.setString(5, description)
            s.setObject(6, SYSTEM_ACTOR_ID)
            s.executeUpdate()
        }
    }

    @Suppress("LongParameterList")
    private fun line(
        c: Connection,
        id: UUID,
        journalId: UUID,
        glAccountId: UUID,
        side: String,
        amount: BigDecimal,
        currency: String,
        baseAmount: BigDecimal,
        sequence: Int,
    ) {
        c.prepareStatement(
            """insert into journal_lines (id, journal_id, gl_account_id, side, amount, currency_code, base_amount, base_currency, sequence)
               values (?, ?, ?, ?, ?, ?, ?, 'CZK', ?)
               on conflict (id) do nothing""",
        ).use { s ->
            s.setObject(1, id)
            s.setObject(2, journalId)
            s.setObject(3, glAccountId)
            s.setString(4, side)
            s.setBigDecimal(5, amount)
            s.setString(6, currency)
            s.setBigDecimal(7, baseAmount)
            s.setInt(8, sequence)
            s.executeUpdate()
        }
    }

    private val CZK_AMOUNT = BigDecimal("250000.00")
    private val CZK_JOURNAL_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010896")
    private val CZK_TRANSACTION_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010897")
    private val CZK_NOSTRO_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010898")
    private val CZK_PLACEMENT_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000010899")

    private val EUR_AMOUNT = BigDecimal("10000.00")
    private val EUR_BASE_AMOUNT = BigDecimal("251000.00")
    private val EUR_JOURNAL_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000011107")
    private val EUR_TRANSACTION_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000011108")
    private val EUR_NOSTRO_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000011109")
    private val EUR_PLACEMENT_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000011110")

    // TreasuryChart.glAccountId(...) — seeded by V29 with these fixed ids.
    private val NOSTRO_CZK_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000001001")
    private val NOSTRO_EUR_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000001002")
    private val PLACEMENT_CZK_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000001500")
    private val PLACEMENT_EUR_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000001501")
    private val SYSTEM_ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
}
