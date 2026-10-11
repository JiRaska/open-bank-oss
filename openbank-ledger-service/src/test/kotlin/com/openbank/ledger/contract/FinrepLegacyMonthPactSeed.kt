// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.contract

import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import com.openbank.ledger.domain.model.TrialBalanceLine
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Historical finrep consumer version 4c86c6f expects exactly one June 2026 closed period.
 * Keep its original Pact state separate from the cumulative 2000 fixture used by current finrep.
 */
object FinrepLegacyMonthPactSeed {
    fun seed(dataSource: DataSource) = dataSource.connection.use { c ->
        FinrepFrozenMonthPactSeed.resetFrozenPeriodEvidence(c)
        val period = PeriodType.MONTH.of(LocalDate.of(2026, 6, 30))
        val lines = listOf(
            TrialBalanceLine(
                ASSET_ID,
                "1100",
                "Cash",
                GlAccountType.ASSET,
                "CZK",
                AMOUNT,
                BigDecimal.ZERO,
            ),
            TrialBalanceLine(
                LIABILITY_ID,
                "2100",
                "Deposits",
                GlAccountType.LIABILITY,
                "CZK",
                BigDecimal.ZERO,
                AMOUNT,
            ),
        )
        val computedAt = Timestamp.from(Instant.parse("2026-07-01T00:00:00Z"))
        val frozenAt = Timestamp.from(Instant.parse("2026-07-02T00:00:00Z"))
        c.prepareStatement(
            """insert into ledger_closed_period (id, period_type, period_from, period_to, status, evidence_state,
               computed_at, total_debits, total_credits, account_count, content_hash, drafted_by, frozen_by,
               frozen_at, created_at, updated_at)
               values (?, 'MONTH', ?, ?, 'FROZEN', 'LINES_V1', ?, ?, ?, 2, ?, 'maker', 'checker', ?, ?, ?)""",
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
            """insert into ledger_closed_period_trial_balance_line
               (period_id, gl_account_id, currency, code, name, account_type, total_debit, total_credit)
               values (?, ?, 'CZK', ?, ?, ?, ?, ?)""",
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

    private val AMOUNT = BigDecimal("150000")
    private val PERIOD_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000009601")
    private val ASSET_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000009602")
    private val LIABILITY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000009603")
}
