// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Real PostgreSQL proves the immutable calculated evidence constraints before a writer is enabled. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class NavCorrectionJournalSchemaIT {
    private data class Fixture(
        val fund: UUID = UUID.randomUUID(),
        val original: UUID = UUID.randomUUID(),
        val replacement: UUID = UUID.randomUUID(),
        val contract: UUID = UUID.randomUUID(),
        val order: UUID = UUID.randomUUID(),
        val transaction: UUID = UUID.randomUUID(),
    )

    private data class PricedHistory(val rowCount: Long, val transaction: PricedTransaction)

    /** Every V1 column of the priced transaction, including its original instruction linkage. */
    private data class PricedTransaction(
        val id: UUID,
        val orderId: UUID?,
        val contractId: UUID,
        val fundId: UUID,
        val transactionType: String,
        val units: BigDecimal,
        val amount: BigDecimal,
        val navId: UUID,
        val navPerUnit: BigDecimal,
        val pricedAt: OffsetDateTime,
        val correctedFromNavId: UUID?,
    )

    private fun jdbc(): Connection = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    @Test
    fun `calculated journal and obligation commit atomically and cannot be altered or duplicated`() {
        val fixture = Fixture()
        jdbc().use { connection ->
            seed(connection, fixture)
            connection.autoCommit = false
            assertThatThrownBy { insertJournal(connection, fixture.copy(contract = UUID.randomUUID())) }
                .isInstanceOf(SQLException::class.java)
            connection.rollback()
            assertThatThrownBy { insertJournal(connection, fixture, amount = "101") }
                .isInstanceOf(SQLException::class.java)
            connection.rollback()
            insertJournal(connection, fixture)
            assertThatThrownBy { insertDraft(connection, fixture, "51.000000") }
                .isInstanceOf(SQLException::class.java)
            connection.rollback()
            assertThat(count(connection, fixture)).isZero()

            insertJournal(connection, fixture)
            assertThatThrownBy { connection.commit() }
                .isInstanceOf(SQLException::class.java)
                .hasMessageContaining("correction journal units draft is missing")
            connection.rollback()
            assertThat(count(connection, fixture)).isZero()

            insertJournal(connection, fixture)
            insertDraft(connection, fixture, "50.000000")
            connection.commit()
            assertThat(count(connection, fixture)).isEqualTo(1)
        }
        assertImmutableAndUnique(fixture)
        assertCashDraft(fixture)
    }

    @Test
    fun `source price edit and journal insert serialize at read committed`() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            journalInsertWins(worker)
            priceEditWins(worker)
        } finally {
            worker.shutdownNow()
        }
    }

    private fun journalInsertWins(worker: ExecutorService) {
        val journalFirst = Fixture()
        jdbc().use { seed(it, journalFirst) }
        jdbc().use { journalConnection ->
            journalConnection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            journalConnection.autoCommit = false
            insertJournal(journalConnection, journalFirst)
            insertDraft(journalConnection, journalFirst, "50.000000")

            val backend = ArrayBlockingQueue<Int>(1)
            val attemptedEdit: Future<Throwable?> = worker.submit<Throwable?> {
                jdbc().use { editor ->
                    editor.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                    editor.autoCommit = false
                    editor.createStatement().use { it.execute("SET LOCAL statement_timeout = '8s'") }
                    backend.put(backendPid(editor))
                    runCatching {
                        editor.createStatement().use { sql ->
                            sql.executeUpdate(
                                "UPDATE fund_navs SET nav_per_unit = 3 WHERE id = '${journalFirst.original}'",
                            )
                        }
                        editor.commit()
                    }.exceptionOrNull()
                }
            }
            assertWaitingOnSourceLock(backend, attemptedEdit)
            journalConnection.commit()
            assertThat(attemptedEdit.get(8, TimeUnit.SECONDS))
                .isInstanceOf(SQLException::class.java)
                .hasMessageContaining("NAV price used by correction journal is immutable")
        }
    }

    private fun priceEditWins(worker: ExecutorService) {
        val priceFirst = Fixture()
        jdbc().use { seed(it, priceFirst) }
        jdbc().use { editor ->
            editor.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            editor.autoCommit = false
            editor.createStatement().use { sql ->
                sql.executeUpdate("UPDATE fund_navs SET nav_per_unit = 3 WHERE id = '${priceFirst.original}'")
            }

            val backend = ArrayBlockingQueue<Int>(1)
            val attemptedJournal: Future<Throwable?> = worker.submit<Throwable?> {
                jdbc().use { writer ->
                    writer.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                    writer.autoCommit = false
                    writer.createStatement().use { it.execute("SET LOCAL statement_timeout = '8s'") }
                    backend.put(backendPid(writer))
                    runCatching {
                        insertJournal(writer, priceFirst)
                        insertDraft(writer, priceFirst, "50.000000")
                        writer.commit()
                    }.exceptionOrNull()
                }
            }
            assertWaitingOnSourceLock(backend, attemptedJournal)
            editor.commit()
            assertThat(attemptedJournal.get(8, TimeUnit.SECONDS))
                .isInstanceOf(SQLException::class.java)
                .hasMessageContaining("snapshot does not match priced source")
        }
        jdbc().use { assertThat(count(it, priceFirst)).isZero() }
    }

    @Test
    fun `V5 migrates existing priced history without rewriting it`() {
        val schema = "nav_correction_${UUID.randomUUID().toString().replace("-", "")}"
        val fixture = Fixture()
        assertThat(flyway(schema, "4").migrate().migrationsExecuted).isEqualTo(4)
        val before = jdbc().use { connection ->
            connection.schema = schema
            seed(connection, fixture)
            pricedHistory(connection, fixture)
        }
        assertThat(before.rowCount).isEqualTo(1)
        assertThat(before.transaction.id).isEqualTo(fixture.transaction)
        assertThat(before.transaction.orderId).isEqualTo(fixture.order)

        assertThat(flyway(schema, "5").migrate().migrationsExecuted).isEqualTo(1)
        jdbc().use { connection ->
            connection.schema = schema
            assertThat(pricedHistory(connection, fixture)).isEqualTo(before)
            connection.autoCommit = false
            insertJournal(connection, fixture)
            insertDraft(connection, fixture, "50.000000")
            connection.commit()
            assertThat(count(connection, fixture)).isEqualTo(1)
            assertThat(pricedHistory(connection, fixture)).isEqualTo(before)
        }
    }

    private fun flyway(schema: String, target: String): Flyway = ConfigProvider.getConfig().let { config ->
        Flyway.configure()
            .dataSource(
                config.getValue("quarkus.datasource.jdbc.url", String::class.java),
                config.getValue("quarkus.datasource.username", String::class.java),
                config.getValue("quarkus.datasource.password", String::class.java),
            )
            .schemas(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .target(target)
            .load()
    }

    private fun pricedHistory(connection: Connection, fixture: Fixture): PricedHistory {
        val rowCount = connection.createStatement().use { sql ->
            sql.executeQuery("SELECT count(*) FROM unit_transactions").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
        val transaction = connection.prepareStatement(
            """SELECT id, order_id, contract_id, fund_id, transaction_type, units, amount,
                      nav_id, nav_per_unit, priced_at, corrected_from_nav_id
               FROM unit_transactions WHERE id = ?""",
        ).use { sql ->
            sql.setObject(1, fixture.transaction)
            sql.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                val row = PricedTransaction(
                    rows.getObject("id", UUID::class.java),
                    rows.getObject("order_id", UUID::class.java),
                    rows.getObject("contract_id", UUID::class.java),
                    rows.getObject("fund_id", UUID::class.java),
                    rows.getString("transaction_type"),
                    rows.getBigDecimal("units"),
                    rows.getBigDecimal("amount"),
                    rows.getObject("nav_id", UUID::class.java),
                    rows.getBigDecimal("nav_per_unit"),
                    rows.getObject("priced_at", OffsetDateTime::class.java),
                    rows.getObject("corrected_from_nav_id", UUID::class.java),
                )
                assertThat(rows.next()).isFalse()
                row
            }
        }
        return PricedHistory(rowCount, transaction)
    }

    private fun backendPid(connection: Connection): Int = connection.createStatement().use { sql ->
        sql.executeQuery("SELECT pg_backend_pid()").use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun assertWaitingOnSourceLock(backend: ArrayBlockingQueue<Int>, attempt: Future<Throwable?>) {
        val pid = requireNotNull(backend.poll(5, TimeUnit.SECONDS)) { "writer backend did not start" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val blocked = jdbc().use { observer ->
            while (System.nanoTime() < deadline) {
                if (blockingCount(observer, pid) > 0) return@use true
                Thread.sleep(20)
            }
            false
        }
        assertThat(blocked).describedAs("writer backend must wait on a PostgreSQL lock").isTrue()
        assertThat(attempt.isDone).isFalse()
    }

    private fun blockingCount(observer: Connection, pid: Int): Int =
        observer.prepareStatement("SELECT cardinality(pg_blocking_pids(?))").use { sql ->
            sql.setInt(1, pid)
            sql.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun seed(connection: Connection, fixture: Fixture) {
        val isin = "CZ${fixture.fund.toString().replace("-", "").take(10)}"
        connection.createStatement().use { sql ->
            sql.executeUpdate(
                """INSERT INTO funds
                   (id, name, isin, lei, depositary_reference, custody_account_reference,
                    currency, risk_class, mandatory_conservative, management_fee_rate,
                    launch_nav_per_unit, status, created_at, updated_at)
                   VALUES ('${fixture.fund}', 'Journal test', '$isin', '12345678901234567890',
                           'test-depositary', 'test-custody', 'CZK', 3, false, 0.01, 1,
                           'ACTIVE', now(), now())""",
            )
            sql.executeUpdate(
                """INSERT INTO fund_navs
                   (id, fund_id, valuation_date, gross_assets, accrued_management_fee,
                    other_liabilities, net_assets, units_outstanding, nav_per_unit,
                    status, calculated_by, calculated_at, approved_by, published_at)
                   VALUES ('${fixture.original}', '${fixture.fund}', DATE '2026-10-10', 100, 0, 0, 100, 50, 2,
                           'PUBLISHED', 'maker', now(), 'checker', now())""",
            )
            sql.executeUpdate(
                """INSERT INTO fund_navs
                   (id, fund_id, valuation_date, gross_assets, accrued_management_fee,
                    other_liabilities, net_assets, units_outstanding, nav_per_unit,
                    status, calculated_by, calculated_at, corrects_nav_id)
                   VALUES ('${fixture.replacement}', '${fixture.fund}', DATE '2026-10-10', 100, 0, 0, 100, 50, 1,
                           'CALCULATED', 'maker', now(), '${fixture.original}')""",
            )
            sql.executeUpdate(
                """INSERT INTO unit_orders
                   (id, contract_id, fund_id, order_type, amount, status, placed_at, settled_at, nav_id)
                   VALUES ('${fixture.order}', '${fixture.contract}', '${fixture.fund}', 'SUBSCRIBE', 100, 'SETTLED',
                           now() - interval '1 day', now(), '${fixture.original}')""",
            )
            sql.executeUpdate(
                """INSERT INTO unit_transactions
                   (id, order_id, contract_id, fund_id, transaction_type, units, amount,
                    nav_id, nav_per_unit, priced_at)
                   VALUES ('${fixture.transaction}', '${fixture.order}', '${fixture.contract}',
                           '${fixture.fund}', 'SUBSCRIBE',
                           50, 100, '${fixture.original}', 2, now())""",
            )
        }
    }

    private fun assertImmutableAndUnique(fixture: Fixture) {
        jdbc().use { connection ->
            connection.createStatement().use { sql ->
                assertThatThrownBy {
                    sql.executeUpdate(
                        "UPDATE nav_correction_journal SET reason = 'rewritten' " +
                            "WHERE replacement_nav_id = '${fixture.replacement}'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    sql.executeUpdate(
                        "DELETE FROM nav_correction_obligation_drafts " +
                            "WHERE replacement_nav_id = '${fixture.replacement}'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    sql.executeUpdate(
                        "UPDATE unit_transactions SET amount = 101 WHERE id = '${fixture.transaction}'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    sql.executeUpdate(
                        "UPDATE fund_navs SET nav_per_unit = 3 WHERE id = '${fixture.original}'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    sql.executeUpdate(
                        "UPDATE fund_navs SET nav_per_unit = 3 WHERE id = '${fixture.replacement}'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy { sql.execute("TRUNCATE nav_correction_obligation_drafts") }
                    .isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    sql.executeUpdate(
                        """INSERT INTO nav_correction_obligation_drafts
                           (replacement_nav_id, original_transaction_id, contract_id, fund_id,
                            currency, kind, signed_delta)
                           VALUES ('${fixture.replacement}', '${fixture.transaction}',
                                   '${fixture.contract}', '${fixture.fund}',
                                   'CZK', 'UNITS', 50)""",
                    )
                }.isInstanceOf(SQLException::class.java)
            }
        }
    }

    private fun insertJournal(connection: Connection, fixture: Fixture, amount: String = "100") {
        connection.createStatement().use { sql ->
            sql.executeUpdate(
                """INSERT INTO nav_correction_journal
                   (replacement_nav_id, original_transaction_id, original_nav_id, contract_id,
                    fund_id, currency, original_order_id, transaction_type, original_nav_per_unit,
                    replacement_nav_per_unit, original_units, proposed_units, units_delta,
                    original_amount, proposed_amount, cash_delta, rounding_residual, reason,
                    rule_version, maker, checker, effective_at, recorded_at)
                   VALUES ('${fixture.replacement}', '${fixture.transaction}', '${fixture.original}',
                           '${fixture.contract}', '${fixture.fund}',
                           'CZK', '${fixture.order}', 'SUBSCRIBE', 2, 1, 50, 100, 50, $amount, $amount, 0, 0,
                           'pricing correction', 'test-v1', 'maker', 'checker', now(), now())""",
            )
        }
    }

    private fun insertDraft(connection: Connection, fixture: Fixture, delta: String) {
        connection.createStatement().use { sql ->
            sql.executeUpdate(
                """INSERT INTO nav_correction_obligation_drafts
                   (replacement_nav_id, original_transaction_id, contract_id, fund_id,
                    currency, kind, signed_delta)
                   VALUES ('${fixture.replacement}', '${fixture.transaction}',
                           '${fixture.contract}', '${fixture.fund}',
                           'CZK', 'UNITS', $delta)""",
            )
        }
    }

    private fun assertCashDraft(fixture: Fixture) {
        val order = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        jdbc().use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { sql ->
                sql.executeUpdate(
                    """INSERT INTO unit_orders
                       (id, contract_id, fund_id, order_type, units, status, placed_at, settled_at, nav_id)
                       VALUES ('$order', '${fixture.contract}', '${fixture.fund}', 'REDEEM', 2,
                               'SETTLED', now() - interval '1 day', now(), '${fixture.original}')""",
                )
                sql.executeUpdate(
                    """INSERT INTO unit_transactions
                       (id, order_id, contract_id, fund_id, transaction_type, units, amount,
                        nav_id, nav_per_unit, priced_at)
                       VALUES ('$transaction', '$order', '${fixture.contract}', '${fixture.fund}',
                               'REDEEM', 2, 4, '${fixture.original}', 2, now())""",
                )
                sql.executeUpdate(
                    """INSERT INTO nav_correction_journal
                       (replacement_nav_id, original_transaction_id, original_nav_id, contract_id,
                        fund_id, currency, original_order_id, transaction_type, original_nav_per_unit,
                        replacement_nav_per_unit, original_units, proposed_units, units_delta,
                        original_amount, proposed_amount, cash_delta, rounding_residual, reason,
                        rule_version, maker, checker, effective_at, recorded_at)
                       VALUES ('${fixture.replacement}', '$transaction', '${fixture.original}',
                               '${fixture.contract}', '${fixture.fund}', 'CZK', '$order', 'REDEEM',
                               2, 1, 2, 2, 0, 4, 2, -2, 0, 'pricing correction', 'test-v1',
                               'maker', 'checker', now(), now())""",
                )
                val beforeInvalidDraft = connection.setSavepoint()
                assertThatThrownBy {
                    sql.executeUpdate(
                        """INSERT INTO nav_correction_obligation_drafts
                           (replacement_nav_id, original_transaction_id, contract_id, fund_id,
                            currency, kind, signed_delta)
                           VALUES ('${fixture.replacement}', '$transaction', '${fixture.contract}',
                                   '${fixture.fund}', 'CZK', 'CASH', -2.001)""",
                    )
                }.isInstanceOf(SQLException::class.java)
                connection.rollback(beforeInvalidDraft)
                sql.executeUpdate(
                    """INSERT INTO nav_correction_obligation_drafts
                       (replacement_nav_id, original_transaction_id, contract_id, fund_id,
                        currency, kind, signed_delta)
                       VALUES ('${fixture.replacement}', '$transaction', '${fixture.contract}',
                               '${fixture.fund}', 'CZK', 'CASH', -2.00)""",
                )
            }
            connection.commit()
        }
    }

    private fun count(connection: Connection, fixture: Fixture): Int = connection.createStatement().use { sql ->
        sql.executeQuery(
            "SELECT count(*) FROM nav_correction_journal WHERE replacement_nav_id = '${fixture.replacement}' " +
                "AND original_transaction_id = '${fixture.transaction}'",
        ).use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
}
