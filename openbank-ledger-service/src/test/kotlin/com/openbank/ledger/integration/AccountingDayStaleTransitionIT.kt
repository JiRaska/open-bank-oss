// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.it.PostgresTestResource
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.config.HttpClientConfig.httpClientConfig
import io.restassured.config.RestAssuredConfig.config
import io.restassured.response.Response
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** The losing update uses a consumed version, so the conflict never depends on thread timing. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AccountingDayStaleTransitionIT {
    @Inject
    lateinit var days: AccountingDayRepository

    @Inject
    lateinit var dataSource: DataSource

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun message(day: AccountingDayRecord) =
        OutboxMessage(aggregateId = day.id, eventType = "AccountingDayTransitioned", payload = "{}")

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `HTTP stale write returns conflict and leaves no transition event`() {
        val date = LocalDate.of(2019, 8, 13)
        val opened = AccountingDayRecord.open(date, Instant.now(), "operator", UUID.randomUUID())
        onEventLoop { days.saveOpened(opened, message(opened)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                try {
                    assertHttpStaleWrite(holder, opened, executor)
                } finally {
                    holder.rollback()
                }
            }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).isTrue()
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select status, version, (select count(*) from ledger_outbox where aggregate_id = ?) as events " +
                    "from ledger_accounting_day where id = ?",
            ).use { statement ->
                statement.setObject(1, opened.id)
                statement.setObject(2, opened.id)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("status")).isEqualTo("OPEN")
                    assertThat(rows.getLong("version")).isEqualTo(1L)
                    assertThat(rows.getLong("events")).isEqualTo(1L)
                }
            }
        }
    }

    private fun assertHttpStaleWrite(holder: Connection, opened: AccountingDayRecord, executor: ExecutorService) {
        val holderPid = holder.createStatement().use { statement ->
            statement.executeQuery("select pg_backend_pid()").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
        holder.prepareStatement("update ledger_accounting_day set version = version + 1 where id = ?").use {
            it.setObject(1, opened.id)
            assertThat(it.executeUpdate()).isEqualTo(1)
        }
        val response = executor.submit<Response> {
            given().config(
                config().httpClient(
                    httpClientConfig().setParam("http.connection.timeout", 5000)
                        .setParam("http.socket.timeout", 30000),
                ),
            ).contentType("application/json").`when`()
                .post("/api/v1/ledger/accounting-days/${opened.businessDate}/transitions/CUTOFF")
        }
        // READ COMMITTED sees OPEN/version 0 until the holder commits. Prove the
        // real HTTP update is waiting on this row before releasing its stale read.
        awaitBlockedUpdate(holderPid, response)
        holder.commit()
        val result = response.get(30, TimeUnit.SECONDS)
        assertThat(result.statusCode).isEqualTo(409)
        assertThat(result.jsonPath().getString("error")).contains("expected version 0")
    }

    private fun awaitBlockedUpdate(holderPid: Int, response: Future<Response>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        dataSource.connection.use { observer ->
            while (System.nanoTime() < deadline) {
                assertRequestPending(response)
                if (hasBlockedUpdate(observer, holderPid)) return
                Thread.sleep(25)
            }
        }
        error("HTTP accounting-day update did not wait on the held row lock")
    }

    private fun hasBlockedUpdate(observer: Connection, holderPid: Int): Boolean = observer.prepareStatement(
        "select exists(select 1 from pg_stat_activity where wait_event_type = 'Lock' " +
            "and ? = any(pg_blocking_pids(pid)) and query ilike '%update%ledger_accounting_day%')",
    ).use { statement ->
        statement.queryTimeout = 5
        statement.setInt(1, holderPid)
        statement.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }

    private fun assertRequestPending(response: Future<Response>) {
        if (response.isDone) {
            val early = response.get(1, TimeUnit.SECONDS)
            error("HTTP transition returned ${early.statusCode} before waiting on the row lock")
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `consumed version returns conflict without a second transition or outbox event`() {
        val date = LocalDate.of(2019, 8, 12)
        val opened = AccountingDayRecord.open(date, Instant.now(), "operator", UUID.randomUUID())
        onEventLoop { days.saveOpened(opened, message(opened)) }

        val cutoff = opened.transitionTo(AccountingDayStatus.CUTOFF, "operator", Instant.now())
        onEventLoop { days.saveTransition(cutoff, opened.version, message(cutoff)) }

        assertThatThrownBy {
            onEventLoop { days.saveTransition(cutoff, opened.version, message(cutoff)) }
        }.isInstanceOf(LedgerConflictException::class.java)
            .hasMessageContaining("expected version ${opened.version}")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select status, version from ledger_accounting_day where id = ?",
            ).use { statement ->
                statement.setObject(1, opened.id)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("status")).isEqualTo("CUTOFF")
                    assertThat(rows.getLong("version")).isEqualTo(1L)
                }
            }
            connection.prepareStatement(
                "select count(*) from ledger_outbox where aggregate_id = ? and event_type = ?",
            ).use { statement ->
                statement.setObject(1, opened.id)
                statement.setString(2, "AccountingDayTransitioned")
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isEqualTo(2L)
                }
            }
        }

        // The same dedicated conflict type is wired to the served HTTP operation's 409 mapper.
        given().contentType("application/json").`when`()
            .post("/api/v1/ledger/accounting-days/$date/transitions/CUTOFF")
            .then().statusCode(409)
    }
}
