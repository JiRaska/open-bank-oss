// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Shared arrangement for the ADR-0372 storage-invariant ITs: two throwaway GL accounts, plain JDBC
 * writers that bypass the domain model, and removal of everything again through the declared-repair
 * path so nothing leaks into the trial-balance and tie-out ITs that share the database.
 */
abstract class LedgerStorageInvariantFixture {

    protected val debitAccount = UUID.randomUUID()
    protected val creditAccount = UUID.randomUUID()
    protected val journals = mutableListOf<UUID>()

    @BeforeEach
    fun createAccounts() {
        connection().use { c ->
            listOf(debitAccount to "ASSET", creditAccount to "LIABILITY").forEach { (id, type) ->
                c.prepareStatement(
                    "insert into gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) " +
                        "values (?, ?, 'ADR-0372 storage invariant fixture', ?, 'CZK', true, true)",
                ).use {
                    it.setObject(1, id)
                    it.setString(2, "S" + id.toString().replace("-", "").take(12))
                    it.setString(3, type)
                    it.executeUpdate()
                }
            }
        }
    }

    @AfterEach
    fun removeFixtures() {
        connection().use { c ->
            c.autoCommit = false
            c.declareRepair("LedgerStorageInvariantIT fixture cleanup")
            c.prepareStatement("delete from journal_lines where gl_account_id in (?, ?)").use {
                it.setObject(1, debitAccount)
                it.setObject(2, creditAccount)
                it.executeUpdate()
            }
            journals.forEach { id ->
                c.prepareStatement("delete from journal_entries where id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
            c.prepareStatement("delete from gl_accounts where id in (?, ?)").use {
                it.setObject(1, debitAccount)
                it.setObject(2, creditAccount)
                it.executeUpdate()
            }
            c.commit()
        }
    }

    protected fun postedJournal(amount: String): UUID {
        val id = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(id, "POSTED")
            c.insertLine(id, debitAccount, "D", amount, 1)
            c.insertLine(id, creditAccount, "C", amount, 2)
            c.commit()
        }
        journals += id
        return id
    }

    protected fun Connection.insertJournal(id: UUID, status: String) {
        prepareStatement(
            "insert into journal_entries (id, transaction_id, entry_date, value_date, status, created_by) " +
                "values (?, ?, ?, ?, ?, ?)",
        ).use {
            it.setObject(1, id)
            it.setObject(2, UUID.randomUUID())
            it.setDate(3, Date.valueOf(ENTRY_DATE))
            it.setDate(4, Date.valueOf(ENTRY_DATE))
            it.setString(5, status)
            it.setObject(6, UUID.fromString(OPERATOR))
            it.executeUpdate()
        }
    }

    protected fun Connection.insertLine(
        journalId: UUID,
        account: UUID,
        side: String,
        amount: String,
        sequence: Int,
        baseCurrency: String = "CZK",
        baseAmount: String = amount,
    ) {
        prepareStatement(
            "insert into journal_lines (journal_id, gl_account_id, side, amount, currency_code, base_amount, " +
                "base_currency, sequence) values (?, ?, ?, ?, 'CZK', ?, ?, ?)",
        ).use {
            it.setObject(1, journalId)
            it.setObject(2, account)
            it.setString(3, side)
            it.setBigDecimal(4, BigDecimal(amount))
            it.setBigDecimal(5, BigDecimal(baseAmount))
            it.setString(6, baseCurrency)
            it.setInt(7, sequence)
            it.executeUpdate()
        }
    }

    protected fun Connection.insertAlternatingLine(journalId: UUID, n: Int, amount: String) {
        val debit = n % 2 == 1
        insertLine(journalId, if (debit) debitAccount else creditAccount, if (debit) "D" else "C", amount, n)
    }

    protected fun Connection.declareRepair(reason: String) {
        prepareStatement("select ledger_declare_repair(?)").use {
            it.setString(1, reason)
            it.execute()
        }
    }

    protected fun count(fromWhere: String, id: UUID): Long = connection().use { c ->
        c.prepareStatement("select count(*) from $fromWhere").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    protected fun status(id: UUID): String = connection().use { c ->
        c.prepareStatement("select status from journal_entries where id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                check(rows.next()) { "no journal_entries row for $id" }
                rows.getString(1)
            }
        }
    }

    protected fun lineAmounts(id: UUID): List<BigDecimal> = connection().use { c ->
        c.prepareStatement("select base_amount from journal_lines where journal_id = ? order by sequence").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getBigDecimal(1)) }
            }
        }
    }

    protected fun audit(reason: String): List<String> = connection().use { c ->
        c.prepareStatement(
            "select table_name || ' ' || operation || ' ' || journal_id || " +
                "coalesce(' ' || changed_columns::text, '') " +
                "from ledger_repair_audit where reason = ? and database_role = session_user " +
                "and recorded_at > now() - interval '10 minutes' order by id",
        ).use {
            it.setString(1, reason)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    protected fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    protected fun org.assertj.core.api.AbstractThrowableAssert<*, *>.isBalanceViolation(
        fragment: String = "is not balanced in",
    ) = isInstanceOfSatisfying(SQLException::class.java) {
        assertThat(it.sqlState).describedAs(it.message).isEqualTo("23514")
        assertThat(it.message).contains(fragment)
    }

    protected fun org.assertj.core.api.AbstractThrowableAssert<*, *>.isAppendOnlyViolation() =
        isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).describedAs(it.message).isEqualTo("55000")
            assertThat(it.message).contains("append-only")
        }

    protected companion object {
        const val OPERATOR = "00000000-0000-0000-0000-000000000372"
        val ENTRY_DATE: LocalDate = LocalDate.now()
    }
}
