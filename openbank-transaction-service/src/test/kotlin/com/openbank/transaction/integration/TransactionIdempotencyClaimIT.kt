// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Direct SQL deliberately acts like an old application binary: the database must claim keys
 * even when a writer knows nothing about the new table. The two dates use different partitions.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionIdempotencyClaimIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `a key claimed in one year cannot book again in another year`() {
        val key = UUID.randomUUID().toString()
        val firstId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            insertTransaction(connection, firstId, key, LocalDate.of(2025, 6, 1))
        }

        val error = org.junit.jupiter.api.assertThrows<SQLException> {
            dataSource.connection.use { connection ->
                insertTransaction(connection, UUID.randomUUID(), key, LocalDate.of(2026, 6, 1))
            }
        }
        assertThat(error.sqlState).isEqualTo("23505")
        assertThat(error.message).contains("idempotency")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT transaction_id, booking_date FROM transaction_idempotency_claims WHERE idempotency_key = ?",
            ).use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject("transaction_id", UUID::class.java)).isEqualTo(firstId)
                    assertThat(rows.getDate("booking_date").toLocalDate()).isEqualTo(LocalDate.of(2025, 6, 1))
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `claim and booking share the same database transaction`() {
        val key = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            insertTransaction(connection, UUID.randomUUID(), key, LocalDate.of(2026, 7, 1))
            connection.prepareStatement(
                """
                SELECT t.xmin::text AS booking_xmin, c.xmin::text AS claim_xmin
                FROM transactions t JOIN transaction_idempotency_claims c
                  ON c.idempotency_key = t.idempotency_key
                WHERE t.idempotency_key = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("claim_xmin")).isEqualTo(rows.getString("booking_xmin"))
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `a rolled back booking leaves no claim`() {
        val key = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            insertTransaction(connection, UUID.randomUUID(), key, LocalDate.of(2026, 8, 1))
            connection.rollback()
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM transaction_idempotency_claims WHERE idempotency_key = ?",
            ).use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isZero()
                }
            }
        }
    }

    @Test
    fun `concurrent bookings in different years produce one winner`() {
        val key = UUID.randomUUID().toString()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(LocalDate.of(2025, 9, 1), LocalDate.of(2026, 9, 1)).map { date ->
                workers.submit<String> {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        try {
                            insertTransaction(connection, UUID.randomUUID(), key, date)
                            connection.commit()
                            "committed"
                        } catch (e: SQLException) {
                            connection.rollback()
                            e.sqlState
                        }
                    }
                }
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(attempts.map { it.get(20, TimeUnit.SECONDS) })
                .containsExactlyInAnyOrder("committed", "23505")
        } finally {
            start.countDown()
            workers.shutdownNow()
        }
        dataSource.connection.use { connection ->
            assertThat(countByKey(connection, "transactions", key)).isEqualTo(1)
            assertThat(countByKey(connection, "transaction_idempotency_claims", key)).isEqualTo(1)
        }
    }

    @Test
    fun `failed outbox insert rolls the booking and claim back together`() {
        val key = UUID.randomUUID().toString()
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            insertTransaction(connection, id, key, LocalDate.of(2026, 10, 1))
            val error = org.junit.jupiter.api.assertThrows<SQLException> {
                connection.prepareStatement(
                    """
                    INSERT INTO transaction_outbox (event_id, aggregate_id, event_type, payload, status)
                    VALUES (NULL, ?, 'transaction.initiated', '{}', 'PENDING')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeUpdate()
                }
            }
            assertThat(error.sqlState).isEqualTo("23502")
            connection.rollback()
        }
        dataSource.connection.use { connection ->
            assertThat(countByKey(connection, "transactions", key)).isZero()
            assertThat(countByKey(connection, "transaction_idempotency_claims", key)).isZero()
        }
    }

    @Test
    fun `an update cannot change a claimed idempotency key`() {
        val key = UUID.randomUUID().toString()
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            insertTransaction(connection, id, key, LocalDate.of(2026, 11, 1))
            val error = org.junit.jupiter.api.assertThrows<SQLException> {
                connection.prepareStatement(
                    "UPDATE transactions SET idempotency_key = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, UUID.randomUUID().toString())
                    statement.setObject(2, id)
                    statement.executeUpdate()
                }
            }
            assertThat(error.sqlState).isEqualTo("23514")
            assertThat(countByKey(connection, "transactions", key)).isEqualTo(1)
            assertThat(countByKey(connection, "transaction_idempotency_claims", key)).isEqualTo(1)
        }
    }

    @Test
    fun `an update cannot move a claimed booking to another year`() {
        val key = UUID.randomUUID().toString()
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            insertTransaction(connection, id, key, LocalDate.of(2025, 12, 1))
            val error = org.junit.jupiter.api.assertThrows<SQLException> {
                connection.prepareStatement("UPDATE transactions SET booking_date = ? WHERE id = ?").use { statement ->
                    statement.setObject(1, LocalDate.of(2026, 12, 1))
                    statement.setObject(2, id)
                    statement.executeUpdate()
                }
            }
            assertThat(error.sqlState).isEqualTo("23514")
            assertThat(countByKey(connection, "transactions", key)).isEqualTo(1)
            assertThat(countByKey(connection, "transaction_idempotency_claims", key)).isEqualTo(1)
        }
    }

    private fun countByKey(connection: Connection, table: String, key: String): Long =
        connection.prepareStatement("SELECT count(*) FROM $table WHERE idempotency_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }

    private fun insertTransaction(connection: Connection, id: UUID, key: String, date: LocalDate) {
        connection.prepareStatement(
            """
            INSERT INTO transactions (
                id, reference_number, type, amount, currency_code, base_amount,
                base_currency_code, value_date, booking_date, idempotency_key
            ) VALUES (?, ?, 'CREDIT', 1.00, 'CZK', 1.00, 'CZK', ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, "claim-it-$id")
            statement.setObject(3, date)
            statement.setObject(4, date)
            statement.setString(5, key)
            statement.executeUpdate()
        }
    }
}
