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
 * thread carries no Vert.x context. The default seed is idempotent (`on conflict do nothing`).
 * Broker replay derives the month from the selected pact's request paths and uses an explicit
 * reset in a class-restricted test database. Reset refuses foreign period IDs, retains the
 * immutable evidence trigger, and commits cleanup and reseeding together.
 */
object FinrepFrozenMonthPactSeed {
    val REPORTING_MONTH: LocalDate = LocalDate.of(2000, 6, 30)

    internal fun requestPlan(paths: List<String>): Pair<LocalDate, Boolean> {
        val months = paths.mapNotNull {
            Regex("/MONTH/(\\d{4}-\\d{2}-\\d{2})/").find(it)?.groupValues?.get(1)
        }.toSet()
        require(months.size == 1) { "FINREP pact must declare one unambiguous reporting month in its requests" }
        return LocalDate.parse(months.single()) to paths.any { it.endsWith("frozen-year-to-date-trial-balance") }
    }

    fun seed(
        dataSource: DataSource,
        reportingMonth: LocalDate = REPORTING_MONTH,
        yearToDate: Boolean = true,
        resetFixture: Boolean = false,
    ) = dataSource.connection.use { c ->
        require(reportingMonth.dayOfMonth == reportingMonth.lengthOfMonth())
        c.autoCommit = false
        try {
            if (resetFixture) resetOwnedEvidence(c)
            journal(c, reportingMonth.withDayOfMonth(15))
            line(c, CASH_LINE_ID, CASH_GL_ID, "D", 1)
            line(c, DEPOSITS_LINE_ID, DEPOSITS_GL_ID, "C", 2)
            if (yearToDate) {
                (1 until reportingMonth.monthValue).forEach { month ->
                    emptyEvidence(c, reportingMonth.year, month)
                }
            }
            evidence(c, reportingMonth)
            c.commit()
        } catch (failure: Throwable) {
            c.rollback()
            throw failure
        }
    }

    // Remove only rows owned by this fixture, never unrelated journal or closed-period data.
    private fun resetOwnedEvidence(c: Connection) {
        val periodIds = (9601..9612).map { UUID.fromString("00000000-0000-0000-0000-%012d".format(it)) }
        // This reset is only for a dedicated test database. Refuse any foreign evidence before
        // truncating fixture rows; TRUNCATE leaves the immutable UPDATE/DELETE trigger installed.
        c.createStatement().use { statement ->
            statement.execute(
                "lock table ledger_closed_period, ledger_closed_period_trial_balance_line in access exclusive mode",
            )
            statement.executeQuery("select id from ledger_closed_period").use { rows ->
                while (rows.next()) {
                    check(rows.getObject(1, UUID::class.java) in periodIds) {
                        "Cannot reset a database containing foreign closed-period evidence"
                    }
                }
            }
            statement.execute("truncate table ledger_closed_period_trial_balance_line")
        }
        c.prepareStatement("delete from ledger_closed_period where id = ?").use { statement ->
            periodIds.forEach {
                statement.setObject(1, it)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        c.prepareStatement("delete from journal_lines where id in (?, ?)").use { statement ->
            statement.setObject(1, CASH_LINE_ID)
            statement.setObject(2, DEPOSITS_LINE_ID)
            statement.executeUpdate()
        }
        c.prepareStatement("delete from journal_entries where id = ?").use { statement ->
            statement.setObject(1, JOURNAL_ID)
            statement.executeUpdate()
        }
    }

    private fun emptyEvidence(c: Connection, year: Int, month: Int) {
        val period = PeriodType.MONTH.of(LocalDate.of(year, month, 1))
        val hash = PeriodTrialBalance(period, emptyList()).contentHash()
        val periodId = UUID.fromString("00000000-0000-0000-0000-%012d".format(9601 + month))
        val computedAt = Timestamp.from(Instant.parse("2000-07-01T00:00:00Z"))
        val frozenAt = Timestamp.from(Instant.parse("2000-07-02T00:00:00Z"))
        c.prepareStatement(
            """insert into ledger_closed_period (id, period_type, period_from, period_to, status, evidence_state, computed_at, total_debits, total_credits, account_count, content_hash, drafted_by, frozen_by, frozen_at, created_at, updated_at)
               values (?, 'MONTH', ?, ?, 'FROZEN', 'LINES_V1', ?, 0, 0, 0, ?, 'maker', 'checker', ?, ?, ?)
               on conflict (period_type, period_from) do nothing""",
        ).use { s ->
            s.setObject(1, periodId)
            s.setObject(2, java.sql.Date.valueOf(period.from))
            s.setObject(3, java.sql.Date.valueOf(period.to))
            s.setTimestamp(4, computedAt)
            s.setString(5, hash)
            s.setTimestamp(6, frozenAt)
            s.setTimestamp(7, computedAt)
            s.setTimestamp(8, frozenAt)
            s.executeUpdate()
        }
    }

    private fun journal(c: Connection, entryDate: LocalDate) = c.prepareStatement(
        """insert into journal_entries (id, transaction_id, entry_date, value_date, description, status, created_by)
           values (?, ?, ?, ?, 'finrep pact frozen month', 'POSTED', ?)
           on conflict (id, entry_date) do nothing""",
    ).use { s ->
        s.setObject(1, JOURNAL_ID)
        s.setObject(2, TRANSACTION_ID)
        s.setObject(3, java.sql.Date.valueOf(entryDate))
        s.setObject(4, java.sql.Date.valueOf(entryDate))
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

    private fun evidence(c: Connection, reportingMonth: LocalDate) {
        val period = PeriodType.MONTH.of(reportingMonth)
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
