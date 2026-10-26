// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the ClickHouse-bound adapters POST, captured in memory — a stand-in for the bronze
 * warehouse answering the two `ClickHouseClient` primitives ([insert] as
 * `INSERT INTO <db>.<table> FORMAT JSONEachRow`, [query] as anything else) over the same JDK-HttpServer
 * idiom the campaign suite uses for the silver layer.
 *
 * The suite asserts on captured `bronze_events` / `dead_letter_events` rows, so the FULL durable
 * path under test is real: the consumer's envelope mapping, masking, retry, the sink's JSONEachRow
 * serialization, the `ReplacingMergeTree`-style dedupe contract, the DLQ sink's `raw_payload`
 * fidelity — only the database at the end of the HTTP call is fake.
 *
 * [FakeClickHouseStore.failBronzeInserts] flips the sink-failure scenario: bronze inserts then
 * answer HTTP 500, which the sink surfaces as an exception so the consumer's retry-then-nack path
 * hands the record to the connector's `dead-letter-queue` strategy on the real broker.
 */
object FakeClickHouseStore {

    data class InsertedRow(val table: String, val body: String)

    val inserts = CopyOnWriteArrayList<InsertedRow>()

    /** When set, `bronze_events` inserts answer HTTP 500 — the injected dependency failure. */
    val failBronzeInserts = AtomicBoolean(false)

    fun rows(table: String): List<InsertedRow> = inserts.filter { it.table == table }

    fun clear() {
        inserts.clear()
        failBronzeInserts.set(false)
    }
}

class FakeClickHouseResource : QuarkusTestResourceLifecycleManager {

    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val rawQuery = exchange.requestURI.rawQuery ?: ""
                val sql = Regex("(?:^|&)query=([^&]*)")
                    .find(rawQuery)
                    ?.groupValues
                    ?.get(1)
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                    .orEmpty()
                if (sql.startsWith("INSERT INTO", ignoreCase = true)) {
                    handleInsert(sql, exchange)
                } else {
                    // Reads (reconciliation scans, the WORM `latest` anchor) get an empty grid.
                    answer(exchange, HTTP_OK, "")
                }
            } catch (e: Exception) {
                answer(exchange, HTTP_ERROR, e.message.orEmpty())
            } finally {
                exchange.close()
            }
        }
        server.start()
        return mapOf("openbank.analytics.clickhouse.url" to "http://127.0.0.1:${server.address.port}")
    }

    override fun stop() {
        server.stop(0)
    }

    private fun handleInsert(sql: String, exchange: HttpExchange) {
        val table = Regex("INSERT INTO\\s+\\S+\\.(\\w+)", RegexOption.IGNORE_CASE)
            .find(sql)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        if (table == "bronze_events" && FakeClickHouseStore.failBronzeInserts.get()) {
            answer(exchange, HTTP_ERROR, "injected bronze write failure")
            return
        }
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        if (table.isNotEmpty() && body.isNotBlank()) {
            FakeClickHouseStore.inserts.add(FakeClickHouseStore.InsertedRow(table, body))
        }
        answer(exchange, HTTP_OK, "")
    }

    private fun answer(exchange: HttpExchange, status: Int, body: String) {
        exchange.sendResponseHeaders(status, body.length.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_ERROR = 500
    }
}
