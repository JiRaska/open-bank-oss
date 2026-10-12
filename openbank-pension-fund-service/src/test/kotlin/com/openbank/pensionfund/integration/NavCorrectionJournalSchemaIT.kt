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
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

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

    private fun seed(connection: Connection, fixture: Fixture) {
        connection.createStatement().use { sql ->
            sql.executeUpdate(
                """INSERT INTO funds
                   (id, name, isin, lei, depositary_reference, custody_account_reference,
                    currency, risk_class, mandatory_conservative, management_fee_rate,
                    launch_nav_per_unit, status, created_at, updated_at)
                   VALUES ('${fixture.fund}', 'Journal test', 'CZ0000000001', '12345678901234567890',
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
