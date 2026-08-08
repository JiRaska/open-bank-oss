// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.clickhouse

import com.openbank.analytics.infrastructure.reconcile.ClickHouseWarehouseStateReader
import com.openbank.libs.analytics.AggregateKey
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Optional

/**
 * Guards the one thing no other layer in this module can see: **which database the reads resolve
 * against** (#3991).
 *
 * The other ClickHouse tests stub [ClickHouseClient.query], so they never observe the request URI;
 * the SQL is a `private companion object` constant and the parsers are pure, so a parser test passes
 * against a query that 404s in production. Only driving the real transport can be wrong out loud —
 * the same discipline as the external-feed rule.
 *
 * [FakeClickHouse] is a JDK [HttpServer] (no new dependency, no ClickHouse) that reproduces the
 * deployed shape exactly: the tables exist in `openbank_analytics` and **nothing exists in
 * `default`**, so an unqualified read with no session database answers the real
 * `404 … Code: 60 … (UNKNOWN_TABLE)` body. Against the pre-fix client — no `database=` on the URI —
 * every assertion here fails with `ClickHouse query failed: HTTP 404`.
 */
class ClickHouseClientDatabaseTest {

    /** Minimal ClickHouse-HTTP stand-in: resolves unqualified tables against the session database. */
    private class FakeClickHouse(private val tablesIn: String = "openbank_analytics") {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestedDatabases = mutableListOf<String?>()

        val url: String get() = "http://127.0.0.1:${server.address.port}"

        fun start() {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(exchange: HttpExchange) {
            val params = (exchange.requestURI.rawQuery ?: "").split('&')
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    k to URLDecoder.decode(v, StandardCharsets.UTF_8)
                }
            val database = params["database"]
            requestedDatabases += database
            val sql = params["query"].orEmpty()
            // ClickHouse resolves an unqualified `FROM <table>` against the session database, which
            // defaults to `default` when the request carries no `database` parameter.
            val effective = database ?: "default"
            val qualified = QUALIFIED.containsMatchIn(sql)
            val (status, body) = if (qualified || effective == tablesIn) {
                200 to ROW
            } else {
                404 to UNKNOWN_TABLE_ERROR
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private companion object {
            // A `db.table` reference resolves without a session database — as `insert()` already does.
            val QUALIFIED = Regex("""(FROM|INTO)\s+\w+\.\w+""")
            const val ROW = "ACCOUNT\tacc-1\t5\n"
            const val UNKNOWN_TABLE_ERROR =
                "Code: 60. DB::Exception: Unknown table expression identifier 'bronze_events'. (UNKNOWN_TABLE)"
        }
    }

    private lateinit var fake: FakeClickHouse

    private fun clientAgainst(fake: FakeClickHouse) = ClickHouseClient().apply {
        url = fake.url
        database = "openbank_analytics"
        username = "analytics"
        password = Optional.of("")
    }

    @BeforeEach
    fun startFake() {
        fake = FakeClickHouse()
        fake.start()
    }

    @AfterEach
    fun stopFake() = fake.stop()

    @Test
    fun `the reconciliation read resolves against the configured database, not default`() = runBlocking<Unit> {
        val reader = ClickHouseWarehouseStateReader().apply { clickhouse = clientAgainst(fake) }

        // Pre-fix this throws IllegalStateException("ClickHouse query failed: HTTP 404 … UNKNOWN_TABLE").
        val versions = reader.currentVersions()

        assertThat(versions).containsEntry(AggregateKey("ACCOUNT", "acc-1"), 5L)
        assertThat(fake.requestedDatabases).containsExactly("openbank_analytics")
    }

    @Test
    fun `every read primitive carries the session database`() = runBlocking<Unit> {
        val reader = ClickHouseWarehouseStateReader().apply { clickhouse = clientAgainst(fake) }

        reader.currentVersions()
        reader.rowCountsByType()
        reader.versionsByAggregate()

        assertThat(fake.requestedDatabases).hasSize(3).allMatch { it == "openbank_analytics" }
    }

    @Test
    fun `an insert still targets the configured database`() = runBlocking<Unit> {
        clientAgainst(fake).insert("integrity_anchors", """{"anchor_id":"a-1"}""")

        assertThat(fake.requestedDatabases).containsExactly("openbank_analytics")
    }
}
