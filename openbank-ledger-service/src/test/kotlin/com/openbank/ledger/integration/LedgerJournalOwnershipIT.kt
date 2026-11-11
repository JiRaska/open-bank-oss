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
 * ADR-0372, second half: a journal and all of its lines are written in one transaction, a posted
 * journal has at least two lines on both sides, and no ordering of statements inside a transaction
 * gets an unbalanced posted journal past the commit — including a wide journal, whose per-line
 * checks are handed from line to line rather than repeated.
 *
 * Plain JDBC, domain model bypassed, as in [LedgerStorageInvariantIT]. Run red first against the
 * migration as it stood before these rules: the late append, the line added to a journal just
 * marked REVERSED, the journal stripped of its lines and the journal with no or one-sided lines
 * were all accepted. The ordering and wide-journal tests were green there too — the earlier check
 * re-added every journal once per line — and are here to keep the faster check honest.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
class LedgerJournalOwnershipIT : LedgerStorageInvariantFixture() {

    @Test
    fun `lines cannot be appended to a posted journal by a later transaction`() {
        val id = postedJournal("90.00")
        val reason = "IT late append ${UUID.randomUUID()}"
        connection().use { c ->
            c.autoCommit = false
            // A balancing pair: the balance check alone would let this through.
            assertThatThrownBy { c.insertLine(id, debitAccount, "D", "5.00", 3) }.isAppendOnlyViolation()
            c.rollback()
            // A line whose journal does not exist is refused as well: it could be adopted later.
            assertThatThrownBy { c.insertLine(UUID.randomUUID(), debitAccount, "D", "5.00", 1) }
                .isAppendOnlyViolation()
            c.rollback()

            c.declareRepair(reason)
            c.insertLine(id, debitAccount, "D", "5.00", 3)
            c.insertLine(id, creditAccount, "C", "5.00", 4)
            c.commit()
        }
        assertThat(count("journal_lines where journal_id = ?", id)).isEqualTo(4)
        assertThat(audit(reason)).containsExactly("journal_lines INSERT $id", "journal_lines INSERT $id")
    }

    @Test
    fun `marking a journal reversed does not reopen it for new lines`() {
        // The V10-era defect: the reversal's lines landed on the original journal.
        val original = postedJournal("70.00")
        connection().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                "update journal_entries set status = 'REVERSED', version = version + 1 where id = ? and status = 'POSTED'",
            ).use {
                it.setObject(1, original)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            // The original's row version now belongs to this transaction, exactly like a new journal's.
            assertThatThrownBy { c.insertLine(original, debitAccount, "C", "70.00", 3) }.isAppendOnlyViolation()
            c.rollback()
        }
        assertThat(count("journal_lines where journal_id = ?", original)).isEqualTo(2)
        assertThat(status(original)).isEqualTo("POSTED")
    }

    @Test
    fun `a journal written inside a savepoint still accepts its own lines`() {
        val id = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            val discarded = c.setSavepoint()
            c.insertJournal(UUID.randomUUID(), "POSTED")
            c.rollback(discarded)
            val kept = c.setSavepoint()
            c.insertJournal(id, "POSTED")
            c.insertLine(id, debitAccount, "D", "12.00", 1)
            c.releaseSavepoint(kept)
            val later = c.setSavepoint()
            c.insertLine(id, creditAccount, "C", "12.00", 2)
            c.releaseSavepoint(later)
            c.commit()
        }
        journals += id
        assertThat(count("journal_lines where journal_id = ?", id)).isEqualTo(2)
    }

    @Test
    fun `a posted journal needs at least two lines and both sides`() {
        val empty = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(empty, "POSTED")
            assertThatThrownBy { c.commit() }.isBalanceViolation("fewer than two lines")
        }
        val oneSided = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(oneSided, "POSTED")
            // Zero base amounts net to zero in every currency; only the missing credit side is wrong.
            c.insertLine(oneSided, debitAccount, "D", "10.00", 1, baseAmount = "0")
            c.insertLine(oneSided, creditAccount, "D", "10.00", 2, baseAmount = "0")
            assertThatThrownBy { c.commit() }.isBalanceViolation("both a debit and a credit")
        }
        assertThat(count("journal_entries where id = ?", empty)).isZero()
        assertThat(count("journal_entries where id = ?", oneSided)).isZero()
    }

    @Test
    fun `a declared repair cannot strip a posted journal of its lines`() {
        val id = postedJournal("45.00")
        connection().use { c ->
            c.autoCommit = false
            c.declareRepair("IT strip lines ${UUID.randomUUID()}")
            c.prepareStatement("delete from journal_lines where journal_id = ?").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(2)
            }
            assertThatThrownBy { c.commit() }.isBalanceViolation("fewer than two lines")
        }
        assertThat(count("journal_lines where journal_id = ?", id)).isEqualTo(2)
    }

    @Test
    fun `no ordering inside one transaction lets an unbalanced posted journal through`() {
        // 1. Constraints forced early: the journal is checked while balanced, then a line is added.
        val forcedEarly = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(forcedEarly, "POSTED")
            c.insertLine(forcedEarly, debitAccount, "D", "20.00", 1)
            c.insertLine(forcedEarly, creditAccount, "C", "20.00", 2)
            c.createStatement().use { it.execute("set constraints all immediate") }
            assertThatThrownBy { c.insertLine(forcedEarly, debitAccount, "D", "1.00", 3) }.isBalanceViolation()
            c.rollback()

            // 2. The same, with constraints deferred again before the extra line.
            c.insertJournal(forcedEarly, "POSTED")
            c.insertLine(forcedEarly, debitAccount, "D", "20.00", 1)
            c.insertLine(forcedEarly, creditAccount, "C", "20.00", 2)
            c.createStatement().use {
                it.execute("set constraints all immediate")
                it.execute("set constraints all deferred")
            }
            c.insertLine(forcedEarly, debitAccount, "D", "1.00", 3)
            assertThatThrownBy { c.commit() }.isBalanceViolation()
        }
        assertThat(count("journal_entries where id = ?", forcedEarly)).isZero()

        // 3. Its own lines are append-only from the first statement on, not only after commit.
        val own = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(own, "POSTED")
            c.insertLine(own, debitAccount, "D", "30.00", 1)
            c.insertLine(own, creditAccount, "C", "30.00", 2)
            val before = c.setSavepoint()
            assertThatThrownBy {
                c.prepareStatement("update journal_lines set base_amount = 29 where journal_id = ? and side = 'C'")
                    .use {
                        it.setObject(1, own)
                        it.executeUpdate()
                    }
            }.isAppendOnlyViolation()
            c.rollback(before)
            assertThatThrownBy {
                c.prepareStatement("delete from journal_lines where journal_id = ? and side = 'C'").use {
                    it.setObject(1, own)
                    it.executeUpdate()
                }
            }.isAppendOnlyViolation()
            c.rollback()

            // 4. Under a declared repair the rewrite is allowed and the commit still is not.
            c.insertJournal(own, "POSTED")
            c.insertLine(own, debitAccount, "D", "30.00", 1)
            c.insertLine(own, creditAccount, "C", "30.00", 2)
            c.declareRepair("IT same-transaction rewrite ${UUID.randomUUID()}")
            c.prepareStatement("update journal_lines set base_amount = 29 where journal_id = ? and side = 'C'").use {
                it.setObject(1, own)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            assertThatThrownBy { c.commit() }.isBalanceViolation()
        }
        assertThat(count("journal_entries where id = ?", own)).isZero()
    }

    @Test
    fun `a wide journal is checked as a whole, whichever line is wrong`() {
        val width = 600
        listOf(1, width / 2, width).forEach { wrongLine ->
            val id = UUID.randomUUID()
            connection().use { c ->
                c.autoCommit = false
                c.insertJournal(id, "POSTED")
                for (n in 1..width) {
                    val amount = if (n == wrongLine) "1.01" else "1.00"
                    c.insertAlternatingLine(id, n, amount)
                }
                assertThatThrownBy { c.commit() }.describedAs("wrong line %d", wrongLine).isBalanceViolation()
            }
            assertThat(count("journal_entries where id = ?", id)).isZero()
        }
    }

    @Test
    fun `a wide journal inserted by one statement is checked too, and a balanced one commits`() {
        val width = 600
        // One multi-row statement, where all lines share a command id.
        val bulk = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(bulk, "POSTED")
            c.prepareStatement(
                "insert into journal_lines (journal_id, gl_account_id, side, amount, currency_code, base_amount, " +
                    "base_currency, sequence) select ?, case when g % 2 = 1 then ?::uuid else ?::uuid end, " +
                    "case when g % 2 = 1 then 'D' else 'C' end, 1, 'CZK', " +
                    "case when g = 7 then 2 else 1 end, 'CZK', g from generate_series(1, $width) g",
            ).use {
                it.setObject(1, bulk)
                it.setString(2, debitAccount.toString())
                it.setString(3, creditAccount.toString())
                assertThat(it.executeUpdate()).isEqualTo(width)
            }
            assertThatThrownBy { c.commit() }.isBalanceViolation()
        }
        assertThat(count("journal_entries where id = ?", bulk)).isZero()

        val balanced = UUID.randomUUID()
        connection().use { c ->
            c.autoCommit = false
            c.insertJournal(balanced, "POSTED")
            for (n in 1..width) {
                c.insertAlternatingLine(balanced, n, "1.00")
            }
            c.commit()
        }
        journals += balanced
        assertThat(count("journal_lines where journal_id = ?", balanced)).isEqualTo(width.toLong())
    }
}
