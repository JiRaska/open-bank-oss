// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.contract

import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import com.openbank.ledger.domain.model.TrialBalanceLine
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * finrep-service's "ledger has frozen monthly trial balance for the reporting date" state (#12499).
 *
 * finrep reads `frozen-closing-balance`, which sums every FROZEN month's evidence and refuses
 * unless that sum equals the journal's cumulative balance at month end. So this seeds a COHERENT
 * ledger: one journal in [REPORTING_MONTH] on the V3-seeded accounts 1100/2100, and that month's
 * FROZEN LINES_V1 evidence equal to it. The month is in 2000 so that no other state or IT sharing
 * this container posts before it (the nostro seed posts in 2026, and would otherwise be an
 * unfrozen month inside the cumulative window). Direct JDBC, as [NostroPactSeed], because a state
 * thread carries no Vert.x context. Idempotent: every insert is `on conflict do nothing`.
 */
object FinrepFrozenMonthPactSeed {
    val REPORTING_MONTH: LocalDate = LocalDate.of(2000, 6, 30)

    fun seed(dataSource: DataSource) = dataSource.connection.use { c ->
        journal(c)
        line(c, CASH_LINE_ID, CASH_GL_ID, "D", 1)
        line(c, DEPOSITS_LINE_ID, DEPOSITS_GL_ID, "C", 2)
        evidence(c)
    }

    private fun journal(c: Connection) = c.prepareStatement(
        """insert into journal_entries (id, transaction_id, entry_date, value_date, description, status, created_by)
           values (?, ?, ?, ?, 'finrep pact frozen month', 'POSTED', ?)
           on conflict (id, entry_date) do nothing""",
    ).use { s ->
        s.setObject(1, JOURNAL_ID)
        s.setObject(2, TRANSACTION_ID)
        s.setObject(3, java.sql.Date.valueOf(ENTRY_DATE))
        s.setObject(4, java.sql.Date.valueOf(ENTRY_DATE))
        s.setObject(5, SYSTEM_ACTOR_ID)
        s.executeUpdate()
    }

    private fun line(c: Connection, id: UUID, glAccountId: UUID, side: String, sequence: Int) = c.prepareStatement(
        """insert into journal_lines (id, journal_id, gl_account_id, side, amount, currency_code, base_amount, base_currency, sequence)
           values (?, ?, ?, ?, ?, 'CZK', ?, 'CZK', ?)
           on conflict (id) do nothing""",
    ).use { s ->
        s.setObject(1, id)
        s.setObject(2, JOURNAL_ID)
        s.setObject(3, glAccountId)
        s.setString(4, side)
        s.setBigDecimal(5, AMOUNT)
        s.setBigDecimal(6, AMOUNT)
        s.setInt(7, sequence)
        s.executeUpdate()
    }

    private fun evidence(c: Connection) {
        val period = PeriodType.MONTH.of(REPORTING_MONTH)
        val lines = listOf(
            TrialBalanceLine(
                CASH_GL_ID,
                "1100",
                "Customer Cash Clearing",
                GlAccountType.ASSET,
                "CZK",
                AMOUNT,
                BigDecimal.ZERO,
            ),
            TrialBalanceLine(
                DEPOSITS_GL_ID,
                "2100",
                "Customer Deposit Control",
                GlAccountType.LIABILITY,
                "CZK",
                BigDecimal.ZERO,
                AMOUNT,
            ),
        )
        val computedAt = Timestamp.from(Instant.parse("2000-07-01T00:00:00Z"))
        val frozenAt = Timestamp.from(Instant.parse("2000-07-02T00:00:00Z"))
        c.prepareStatement(
            """insert into ledger_closed_period (id, period_type, period_from, period_to, status, evidence_state, computed_at, total_debits, total_credits, account_count, content_hash, drafted_by, frozen_by, frozen_at, created_at, updated_at)
               values (?, 'MONTH', ?, ?, 'FROZEN', 'LINES_V1', ?, ?, ?, 2, ?, 'maker', 'checker', ?, ?, ?)
               on conflict (period_type, period_from) do nothing""",
        ).use { s ->
            s.setObject(1, PERIOD_ID)
            s.setObject(2, java.sql.Date.valueOf(period.from))
            s.setObject(3, java.sql.Date.valueOf(period.to))
            s.setTimestamp(4, computedAt)
            s.setBigDecimal(5, AMOUNT)
            s.setBigDecimal(6, AMOUNT)
            s.setString(7, PeriodTrialBalance(period, lines).contentHash())
            s.setTimestamp(8, frozenAt)
            s.setTimestamp(9, computedAt)
            s.setTimestamp(10, frozenAt)
            s.executeUpdate()
        }
        c.prepareStatement(
            """insert into ledger_closed_period_trial_balance_line (period_id, gl_account_id, currency, code, name, account_type, total_debit, total_credit)
               values (?, ?, 'CZK', ?, ?, ?, ?, ?) on conflict do nothing""",
        ).use { s ->
            lines.forEach { line ->
                s.setObject(1, PERIOD_ID)
                s.setObject(2, line.glAccountId)
                s.setString(3, line.code)
                s.setString(4, line.name)
                s.setString(5, line.type.name)
                s.setBigDecimal(6, line.totalDebit)
                s.setBigDecimal(7, line.totalCredit)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    private val ENTRY_DATE: LocalDate = LocalDate.of(2000, 6, 15)
    private val AMOUNT = BigDecimal("100.00")
    private val PERIOD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000009601")
    private val JOURNAL_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000012497")
    private val TRANSACTION_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000012498")
    private val CASH_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000012499")
    private val DEPOSITS_LINE_ID: UUID = UUID.fromString("b0000000-0000-0000-0000-000000012500")

    // V3__ledger_governance.sql seeds these with fixed ids.
    private val CASH_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    private val DEPOSITS_GL_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    private val SYSTEM_ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
}
