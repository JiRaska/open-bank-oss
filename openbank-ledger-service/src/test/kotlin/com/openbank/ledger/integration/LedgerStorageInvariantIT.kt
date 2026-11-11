// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0372: the two ledger invariants hold at the storage layer, with the domain model bypassed.
 *
 * Every refusal below is raised by PostgreSQL against rows written over plain JDBC — no
 * `JournalEntry`, no repository, no use case — which is the writer the domain check cannot bind:
 * a migration, a repair script, a manual session. The assertions are on the SQLSTATE the triggers
 * raise and on what is (not) in the tables afterwards, never on the absence of an exception alone.
 *
 * Falsified against the schema without V44 before it was trusted: with the migration file removed
 * all ten tests are red — seven with "Expecting code to raise a throwable", i.e. the database
 * accepted the write, and three because the repair function and audit table do not exist.
 *
 * Journals here live on two GL accounts this class creates, and are removed again through the
 * declared-repair path, so nothing leaks into the trial-balance and tie-out ITs that share the
 * database.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class LedgerStorageInvariantIT {

    private val debitAccount = UUID.randomUUID()
    private val creditAccount = UUID.randomUUID()
    private val journals = mutableListOf<UUID>()

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

    @Test
    fun `an unbalanced posted journal fails at commit and leaves nothing behind`() {
        val id = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(id, "POSTED")
            c.insertLine(id, debitAccount, "D", "100.00", 1)
            c.insertLine(id, creditAccount, "C", "99.99", 2)
            // Deferred: the statements themselves succeed, the transaction cannot commit.
            assertThatThrownBy { c.commit() }.isBalanceViolation()
        }
        assertThat(count("journal_entries where id = ?", id)).isZero()
        assertThat(count("journal_lines where journal_id = ?", id)).isZero()
    }

    @Test
    fun `balance is per settlement currency, so a cross-currency net of zero is still refused`() {
        val id = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(id, "POSTED")
            c.insertLine(id, debitAccount, "D", "100.00", 1, baseCurrency = "CZK")
            c.insertLine(id, creditAccount, "C", "100.00", 2, baseCurrency = "EUR")
            assertThatThrownBy { c.commit() }.isBalanceViolation()
        }
        assertThat(count("journal_entries where id = ?", id)).isZero()
    }

    @Test
    fun `a pending journal may be unbalanced, and cannot be posted until it balances`() {
        val id = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(id, "PENDING")
            c.insertLine(id, debitAccount, "D", "40.00", 1)
            c.commit()
            journals += id

            c.prepareStatement("update journal_entries set status = 'POSTED' where id = ?").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            assertThatThrownBy { c.commit() }.isBalanceViolation()
            c.rollback()

            c.insertLine(id, creditAccount, "C", "40.00", 2)
            c.prepareStatement("update journal_entries set status = 'POSTED' where id = ?").use {
                it.setObject(1, id)
                it.executeUpdate()
            }
            c.commit()
        }
        assertThat(status(id)).isEqualTo("POSTED")
    }

    @Test
    fun `an update of a posted line is refused`() {
        val id = postedJournal("250.00")
        connection().use { c ->
            // Both legs moved together, so the journal would still balance: only append-only stops it.
            assertThatThrownBy {
                c.prepareStatement("update journal_lines set amount = 1, base_amount = 1 where journal_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }.isAppendOnlyViolation()
        }
        assertThat(lineAmounts(id)).containsExactly(BigDecimal("250.000000"), BigDecimal("250.000000"))
    }

    @Test
    fun `a delete of a posted line is refused, and so is a truncate`() {
        val id = postedJournal("75.00")
        connection().use { c ->
            assertThatThrownBy {
                c.prepareStatement("delete from journal_lines where journal_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }.isAppendOnlyViolation()
            // In a transaction that is rolled back either way: were the guard ever missing, this
            // must not empty the table under the sibling ITs that share the database.
            c.autoCommit = false
            assertThatThrownBy {
                c.createStatement().use { it.execute("truncate journal_lines") }
            }.isAppendOnlyViolation()
            c.rollback()
        }
        assertThat(count("journal_lines where journal_id = ?", id)).isEqualTo(2)
    }

    @Test
    fun `a posted journal row accepts the reversal transition and nothing else`() {
        val id = postedJournal("10.00")
        connection().use { c ->
            listOf(
                "update journal_entries set description = 'rewritten' where id = ?",
                "update journal_entries set value_date = value_date + 1 where id = ?",
                "update journal_entries set entry_date = entry_date + 400 where id = ?",
                "update journal_entries set status = 'PENDING' where id = ?",
                "update journal_entries set status = 'REVERSED' where id = ?",
                "update journal_entries set status = 'REVERSED', version = version + 1, synthetic = true where id = ?",
                "delete from journal_entries where id = ?",
            ).forEach { rewrite ->
                assertThatThrownBy {
                    c.prepareStatement(rewrite).use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                }.describedAs(rewrite).isAppendOnlyViolation()
            }
            assertThat(status(id)).isEqualTo("POSTED")

            // The one update the service issues (PanacheJournalRepository.saveReversal).
            c.prepareStatement(
                "update journal_entries set status = 'REVERSED', version = version + 1 where id = ? and status = 'POSTED'",
            ).use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            // REVERSED is terminal.
            assertThatThrownBy {
                c.prepareStatement("update journal_entries set status = 'POSTED' where id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }.isAppendOnlyViolation()
        }
        assertThat(status(id)).isEqualTo("REVERSED")
    }

    @Test
    fun `a declared repair goes through and leaves one audit row per rewritten row`() {
        val id = postedJournal("500.00")
        val reason = "IT repair ${UUID.randomUUID()}"
        connection().use { c ->
            c.autoCommit = false
            c.declareRepair(reason)
            c.prepareStatement("update journal_lines set amount = 400, base_amount = 400 where journal_id = ?").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(2)
            }
            c.prepareStatement("update journal_entries set description = 'repaired' where id = ?").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            c.commit()
        }
        assertThat(lineAmounts(id)).containsExactly(BigDecimal("400.000000"), BigDecimal("400.000000"))
        assertThat(audit(reason)).containsExactlyInAnyOrder(
            "journal_lines UPDATE $id {amount,base_amount}",
            "journal_lines UPDATE $id {amount,base_amount}",
            "journal_entries UPDATE $id {description}",
        )
        connection().use { c ->
            listOf(
                "update ledger_repair_audit set reason = 'laundered'",
                "delete from ledger_repair_audit",
                "truncate ledger_repair_audit",
            ).forEach { tamper ->
                assertThatThrownBy { c.createStatement().use { it.execute(tamper) } }
                    .describedAs(tamper).isAppendOnlyViolation()
            }
        }
    }

    @Test
    fun `a declared repair cannot commit an unbalanced journal, and its audit rows roll back with it`() {
        val id = postedJournal("60.00")
        val reason = "IT unbalanced repair ${UUID.randomUUID()}"
        connection().use { c ->
            c.autoCommit = false
            c.declareRepair(reason)
            c.prepareStatement("update journal_lines set base_amount = 59 where journal_id = ? and side = 'D'").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            assertThatThrownBy { c.commit() }.isBalanceViolation()
        }
        assertThat(lineAmounts(id)).containsExactly(BigDecimal("60.000000"), BigDecimal("60.000000"))
        assertThat(audit(reason)).isEmpty()
    }

    @Test
    fun `the override is bound to its transaction, not to the session`() {
        val id = postedJournal("30.00")
        connection().use { c ->
            val delete = "delete from journal_lines where journal_id = '$id'"
            // A session-level setting, which would survive on a pooled connection, opens nothing.
            c.createStatement().use {
                it.execute("set openbank.ledger_repair_reason = 'session-level setting'")
                it.execute("set openbank.ledger_repair_xid = '1'")
            }
            assertThatThrownBy { c.createStatement().use { it.execute(delete) } }.isAppendOnlyViolation()

            // Declared in autocommit, the declaration ends with its own statement.
            c.declareRepair("declared outside a transaction")
            assertThatThrownBy { c.createStatement().use { it.execute(delete) } }.isAppendOnlyViolation()

            // Declared in one transaction, it does not carry into the next on the same connection.
            c.autoCommit = false
            c.declareRepair("declared in an earlier transaction")
            c.commit()
            assertThatThrownBy { c.createStatement().use { it.execute(delete) } }.isAppendOnlyViolation()
            c.rollback()

            assertThatThrownBy { c.declareRepair("too short") }
                .isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo("22023") }
            c.rollback()
        }
        assertThat(count("journal_lines where journal_id = ?", id)).isEqualTo(2)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a balanced posting and its reversal through the HTTP endpoint still succeed`() {
        val payload = """
            {
              "idempotencyKey": "${UUID.randomUUID()}",
              "transactionId": "${UUID.randomUUID()}",
              "entryDate": "$ENTRY_DATE",
              "valueDate": "$ENTRY_DATE",
              "description": "ADR-0372 storage invariant happy path",
              "createdBy": "$OPERATOR",
              "lines": [
                {"glAccountId": "$debitAccount", "side": "DEBIT", "amount": "1000.00",
                 "currencyCode": "CZK", "baseAmount": "1000.00", "baseCurrencyCode": "CZK"},
                {"glAccountId": "$creditAccount", "side": "CREDIT", "amount": "1000.00",
                 "currencyCode": "CZK", "baseAmount": "1000.00", "baseCurrencyCode": "CZK"}
              ]
            }
        """.trimIndent()
        val id = UUID.fromString(
            (
                Given {
                    contentType("application/json")
                    body(payload)
                } When {
                    post("/api/v1/journals")
                } Then {
                    statusCode(201)
                    body("status", equalTo("POSTED"))
                }
                ).extract().jsonPath().getString("id"),
        )
        journals += id
        assertThat(status(id)).isEqualTo("POSTED")
        assertThat(count("journal_lines where journal_id = ?", id)).isEqualTo(2)

        Given {
            contentType("application/json")
            body("""{"reason": "ADR-0372 lifecycle transition", "reversedBy": "$OPERATOR"}""")
        } When {
            post("/api/v1/journals/$id/reverse")
        } Then {
            statusCode(200)
            body("status", equalTo("REVERSED"))
        }
        val reversal = connection().use { c ->
            c.prepareStatement("select id from journal_entries where reversal_of = ?").use {
                it.setObject(1, id)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "no reversal journal for $id" }
                    rows.getObject(1, UUID::class.java)
                }
            }
        }
        journals += reversal
        // The measured lifecycle transition, performed by the service itself, is not an audited repair.
        assertThat(status(id)).isEqualTo("REVERSED")
        assertThat(status(reversal)).isEqualTo("POSTED")
        assertThat(count("ledger_repair_audit where journal_id = ?", id)).isZero()
    }

    private fun postedJournal(amount: String): UUID {
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

    private fun Connection.insertJournal(id: UUID, status: String) {
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

    private fun Connection.insertLine(
        journalId: UUID,
        account: UUID,
        side: String,
        amount: String,
        sequence: Int,
        baseCurrency: String = "CZK",
    ) {
        prepareStatement(
            "insert into journal_lines (journal_id, gl_account_id, side, amount, currency_code, base_amount, " +
                "base_currency, sequence) values (?, ?, ?, ?, 'CZK', ?, ?, ?)",
        ).use {
            it.setObject(1, journalId)
            it.setObject(2, account)
            it.setString(3, side)
            it.setBigDecimal(4, BigDecimal(amount))
            it.setBigDecimal(5, BigDecimal(amount))
            it.setString(6, baseCurrency)
            it.setInt(7, sequence)
            it.executeUpdate()
        }
    }

    private fun Connection.declareRepair(reason: String) {
        prepareStatement("select ledger_declare_repair(?)").use {
            it.setString(1, reason)
            it.execute()
        }
    }

    private fun count(fromWhere: String, id: UUID): Long = connection().use { c ->
        c.prepareStatement("select count(*) from $fromWhere").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    private fun status(id: UUID): String = connection().use { c ->
        c.prepareStatement("select status from journal_entries where id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                check(rows.next()) { "no journal_entries row for $id" }
                rows.getString(1)
            }
        }
    }

    private fun lineAmounts(id: UUID): List<BigDecimal> = connection().use { c ->
        c.prepareStatement("select base_amount from journal_lines where journal_id = ? order by sequence").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getBigDecimal(1)) }
            }
        }
    }

    private fun audit(reason: String): List<String> = connection().use { c ->
        c.prepareStatement(
            "select table_name || ' ' || operation || ' ' || journal_id || ' ' || changed_columns::text " +
                "from ledger_repair_audit where reason = ? and database_role = session_user " +
                "and recorded_at > now() - interval '10 minutes' order by id",
        ).use {
            it.setString(1, reason)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun org.assertj.core.api.AbstractThrowableAssert<*, *>.isBalanceViolation() =
        isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).describedAs(it.message).isEqualTo("23514")
            assertThat(it.message).contains("is not balanced in")
        }

    private fun org.assertj.core.api.AbstractThrowableAssert<*, *>.isAppendOnlyViolation() =
        isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).describedAs(it.message).isEqualTo("55000")
            assertThat(it.message).contains("append-only")
        }

    private companion object {
        const val OPERATOR = "00000000-0000-0000-0000-000000000372"
        val ENTRY_DATE: LocalDate = LocalDate.now()
    }
}
