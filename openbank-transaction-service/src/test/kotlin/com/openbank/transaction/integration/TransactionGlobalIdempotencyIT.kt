// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Real JDBC contenders and HTTP recovery, deliberately using DIFFERENT booking dates. */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class TransactionGlobalIdempotencyIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `uncommitted global claim serializes cross-date direct partition writers`() {
        val key = UUID.randomUUID().toString()
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { first ->
                first.autoCommit = false
                val winner = insert(first, key, LocalDate.of(2025, 1, 2), "transactions_2025")
                val firstPid = scalar(first, "select pg_backend_pid()")
                val loser = pool.submit<String> {
                    dataSource.connection.use { second ->
                        second.autoCommit = false
                        try {
                            insert(second, key, LocalDate.of(2026, 1, 2), "transactions_2026")
                            second.commit()
                            "COMMITTED"
                        } catch (e: SQLException) {
                            second.rollback()
                            e.sqlState
                        }
                    }
                }
                awaitBlockedBy(firstPid)
                first.commit()
                assertThat(loser.get(30, TimeUnit.SECONDS)).isEqualTo("23505")
                assertThat(count("transactions", key)).isEqualTo(1)
                assertThat(count("transaction_idempotency_keys", key)).isEqualTo(1)
                assertThat(scalar(first, "select count(*) from transactions where id = '$winner'"))
                    .isEqualTo(1)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `HTTP loser misses uncommitted winner then returns its failed status without workflow or outbox`() {
        val key = UUID.randomUUID().toString()
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { first ->
                first.autoCommit = false
                val winner = insert(first, key, LocalDate.of(2025, 2, 3), status = "FAILED")
                val pid = scalar(first, "select pg_backend_pid()")
                val response = pool.submit<io.restassured.response.Response> { post(key) }
                // The winner is invisible to the HTTP pre-lookup. Observe its INSERT waiting on
                // our transaction before releasing it: serial scheduling cannot satisfy this test.
                awaitBlockedBy(pid)
                first.commit()
                val replay = response.get(30, TimeUnit.SECONDS)
                assertThat(replay.statusCode).isEqualTo(201)
                assertThat(replay.jsonPath().getString("id")).isEqualTo(winner.toString())
                assertThat(replay.jsonPath().getString("status")).isEqualTo("FAILED")
                assertThat(count("transactions", key)).isEqualTo(1)
                assertThat(count("transaction_idempotency_keys", key)).isEqualTo(1)
                // Test workflows always mark COMPLETED and emit an outbox event. Neither may run.
                assertThat(scalar(first, "select count(*) from transaction_outbox where aggregate_id = '$winner'"))
                    .isZero()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `every stored status is returned unchanged on replay`() {
        listOf("PENDING", "PROCESSING", "COMPLETED", "FAILED", "REVERSED").forEach { status ->
            val key = UUID.randomUUID().toString()
            val id = dataSource.connection.use { insert(it, key, LocalDate.of(2025, 3, 4), status = status) }
            val replay = post(key)
            assertThat(replay.statusCode).isEqualTo(201)
            assertThat(replay.jsonPath().getString("id")).isEqualTo(id.toString())
            assertThat(replay.jsonPath().getString("status")).isEqualTo(status)
        }
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `orphan registry pointer fails closed`() {
        val key = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into transaction_idempotency_keys (idempotency_key, transaction_id, booking_date) " +
                    "values (?, ?, ?)",
            ).use {
                it.setString(1, key)
                it.setObject(2, UUID.randomUUID())
                it.setObject(3, LocalDate.of(2025, 1, 1))
                it.executeUpdate()
            }
        }
        assertThat(post(key).statusCode).isEqualTo(500)
        assertThat(count("transactions", key)).isZero()
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `missing claim and wrong pointer both fail closed without a new booking`() {
        listOf("delete", "wrong-pointer").forEach { damage ->
            val key = UUID.randomUUID().toString()
            dataSource.connection.use { c ->
                insert(c, key, LocalDate.of(2025, 1, 1))
                c.createStatement().use { statement ->
                    val sql = if (damage == "delete") {
                        "delete from transaction_idempotency_keys where idempotency_key='$key'"
                    } else {
                        "update transaction_idempotency_keys set transaction_id='${UUID.randomUUID()}' " +
                            "where idempotency_key='$key'"
                    }
                    statement.executeUpdate(sql)
                }
            }
            assertThat(post(key).statusCode).isEqualTo(500)
            assertThat(count("transactions", key)).isEqualTo(1)
        }
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `outbox failure rolls back aggregate and registry claim`() {
        val key = UUID.randomUUID().toString()
        dataSource.connection.use { c ->
            c.createStatement().use { statement ->
                statement.execute(
                    """
                    create function reject_global_idempotency_test_outbox() returns trigger
                    language plpgsql as ${'$'}${'$'}
                    begin
                      if exists (select 1 from transactions
                        where id = NEW.aggregate_id and idempotency_key = '$key') then
                        raise exception 'Forced outbox failure';
                      end if;
                      return NEW;
                    end; ${'$'}${'$'};
                    create trigger reject_global_idempotency_test_outbox before insert on transaction_outbox
                    for each row execute function reject_global_idempotency_test_outbox();
                    """.trimIndent(),
                )
                try {
                    assertThat(post(key).statusCode).isEqualTo(500)
                    assertThat(count("transactions", key)).isZero()
                    assertThat(count("transaction_idempotency_keys", key)).isZero()
                } finally {
                    statement.execute("drop trigger reject_global_idempotency_test_outbox on transaction_outbox")
                    statement.execute("drop function reject_global_idempotency_test_outbox()")
                }
            }
        }
    }

    @Test
    fun `duplicate historical keys abort migration without losing either row`() = inIsolatedSchema { c ->
        c.createStatement().use { s ->
            s.execute("insert into transactions values ('a', '${UUID.randomUUID()}', '2025-01-01')")
            s.execute("insert into transactions values ('a', '${UUID.randomUUID()}', '2026-01-01')")
            c.autoCommit = false
            assertThatThrownBy { s.execute(migration()) }.isInstanceOf(SQLException::class.java)
                .hasMessageContaining("requires reconciliation")
            c.rollback()
            assertThat(scalar(c, "select count(*) from transactions")).isEqualTo(2)
            assertThat(
                scalar(
                    c,
                    "select count(*) from pg_class where relname='transaction_idempotency_keys' " +
                        "and relnamespace=current_schema()::regnamespace",
                ),
            ).isZero()
        }
    }

    @Test
    fun `future partition inherits protection and identity cannot move`() = inIsolatedSchema { c ->
        c.autoCommit = false
        c.createStatement().use { s ->
            s.execute(migration())
            c.commit()
            c.autoCommit = true
            s.execute(
                "create table transactions_future partition of transactions " +
                    "for values from ('2040-01-01') to ('2041-01-01')",
            )
            s.execute("insert into transactions_future values ('future', '${UUID.randomUUID()}', '2040-01-01')")
            assertThatThrownBy {
                s.execute("insert into transactions_future values ('future', '${UUID.randomUUID()}', '2040-02-01')")
            }.isInstanceOf(SQLException::class.java).hasMessageContaining("pk_transaction_idempotency_keys")
            assertThatThrownBy { s.execute("update transactions_future set idempotency_key='changed'") }
                .isInstanceOf(SQLException::class.java).hasMessageContaining("immutable")
            assertThat(scalar(c, "select count(*) from transaction_idempotency_keys")).isEqualTo(1)
        }
    }

    private fun migration(): String = checkNotNull(
        javaClass.getResource(
            "/db/migration/V22__global_transaction_idempotency.sql",
        ),
    ).readText()

    private fun inIsolatedSchema(block: (Connection) -> Unit) {
        val schema = "global_idempotency_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("create schema $schema")
                s.execute("set search_path to $schema")
                s.execute(
                    "create table transactions (idempotency_key varchar(100), id uuid, booking_date date) " +
                        "partition by range (booking_date)",
                )
                s.execute(
                    "create table transactions_history partition of transactions " +
                        "for values from ('2025-01-01') to ('2027-01-01')",
                )
                try {
                    block(c)
                } finally {
                    c.rollbackIfNeeded()
                    c.autoCommit = true
                    s.execute("set search_path to public")
                    s.execute("drop schema $schema cascade")
                }
            }
        }
    }

    private fun Connection.rollbackIfNeeded() {
        if (!autoCommit) rollback()
    }

    private fun insert(
        c: Connection,
        key: String,
        date: LocalDate,
        table: String = "transactions",
        status: String = "PENDING",
    ): UUID {
        val id = UUID.randomUUID()
        c.prepareStatement(
            """
            insert into $table (id, reference_number, type, amount, currency_code, base_amount,
              base_currency_code, status, value_date, booking_date, idempotency_key)
            values (?, ?, 'TRANSFER', 1.00, 'CZK', 1.00, 'CZK', ?, ?, ?, ?)
            """.trimIndent(),
        ).use {
            it.setObject(1, id)
            it.setString(2, "GLOBAL-$id")
            it.setString(3, status)
            it.setObject(4, date)
            it.setObject(5, date)
            it.setString(6, key)
            it.executeUpdate()
        }
        return id
    }

    private fun count(table: String, key: String): Long = dataSource.connection.use {
        scalar(it, "select count(*) from $table where idempotency_key='$key'")
    }

    private fun scalar(c: Connection, sql: String): Long = c.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            rows.getLong(1)
        }
    }

    private fun awaitBlockedBy(pid: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        dataSource.connection.use { c ->
            do {
                val blocked = scalar(
                    c,
                    "select count(*) from pg_stat_activity where $pid = any(pg_blocking_pids(pid))",
                )
                if (blocked > 0) return
                Thread.sleep(20)
            } while (System.nanoTime() < deadline)
        }
        error("Expected contender to wait on the uncommitted global claim")
    }

    private fun post(key: String) = RestAssured.given().contentType("application/json").body(
        """
        {"idempotencyKey":"$key", "type":"TRANSFER", "amount":"1.00", "currencyCode":"CZK",
         "sourceAccountId":"${UUID.randomUUID()}", "targetAccountId":"${UUID.randomUUID()}",
         "valueDate":"${LocalDate.now()}"}
        """.trimIndent(),
    ).post("/api/v1/transactions")

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
    }
}
