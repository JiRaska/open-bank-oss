// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.clickhouse

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Thin transport over the ClickHouse HTTP interface, shared by the ClickHouse-native adapters
 * (sink / reconciliation readers / proposal store / WORM mirror).
 *
 * It deliberately uses the JDK [HttpClient] rather than a JDBC driver so the module adds **no new
 * Maven dependency** and stays offline-buildable. Two primitives cover every need:
 *  - [insert] — `INSERT INTO <table> FORMAT JSONEachRow` with a newline-delimited body (idempotency
 *    is delegated to the table engine, e.g. ReplacingMergeTree).
 *  - [query]  — any read; callers append `FORMAT TabSeparated` and parse the tab/newline grid.
 *
 * This bean is always present, but only injected by adapters that are themselves gated by
 * `openbank.analytics.sink.type=clickhouse`, so it costs nothing in the default (logging) profile.
 * It is `open` so tests can subclass and capture/stub the HTTP call without a running server.
 */
@ApplicationScoped
open class ClickHouseClient {

    @ConfigProperty(name = "openbank.analytics.clickhouse.url", defaultValue = "http://localhost:8123")
    lateinit var url: String

    @ConfigProperty(name = "openbank.analytics.clickhouse.database", defaultValue = "openbank_analytics")
    lateinit var database: String

    @ConfigProperty(name = "openbank.analytics.clickhouse.username", defaultValue = "analytics")
    lateinit var username: String

    @ConfigProperty(name = "openbank.analytics.clickhouse.password", defaultValue = "")
    lateinit var password: String

    private val http: HttpClient by lazy { HttpClient.newHttpClient() }

    /** Bulk-inserts a JSONEachRow body (one JSON object per line) into [table] (unqualified — the
     *  configured [database] is prepended). No-op on an empty body. */
    open suspend fun insert(table: String, jsonEachRow: String) {
        if (jsonEachRow.isBlank()) return
        post("INSERT INTO $database.$table FORMAT JSONEachRow", jsonEachRow)
    }

    /** Runs a read [sql] and returns the raw response body. Callers choose the FORMAT and parse it. */
    open suspend fun query(sql: String): String = post(sql, null)

    /** POSTs [sql] (as the `query` parameter) with an optional [body]; throws on a non-2xx response. */
    protected open suspend fun post(sql: String, body: String?): String = withContext(Dispatchers.IO) {
        val uri = URI.create("${url.trimEnd('/')}/?query=${URLEncoder.encode(sql, StandardCharsets.UTF_8)}")
        val request = HttpRequest.newBuilder(uri)
            .header("X-ClickHouse-User", username)
            .header("X-ClickHouse-Key", password)
            .header("Content-Type", "text/plain; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body ?: "", StandardCharsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "ClickHouse query failed: HTTP ${response.statusCode()} ${response.body().take(500)}"
            )
        }
        response.body()
    }
}
