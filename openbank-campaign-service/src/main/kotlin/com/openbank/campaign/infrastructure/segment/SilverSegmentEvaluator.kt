// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.segment

import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentPage
import com.openbank.campaign.domain.model.Segment
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
import java.time.Duration
import java.util.Optional
import java.util.UUID

/**
 * ADR-0210: segment membership is evaluated as a read-only query over the analytics silver layer
 * (`openbank_analytics.silver_current_state`), the same projection the customer-360 BFF reads.
 * The query text is generated exclusively from the typed rule DSL (see `Segment.toWhereClause`) —
 * no SQL ever arrives from a caller. Bind values travel as ClickHouse query parameters, so a rule
 * value can never become SQL.
 */
@ApplicationScoped
class SilverSegmentEvaluator(
    @ConfigProperty(name = "analytics.clickhouse-url", defaultValue = "http://localhost:8123")
    private val clickHouseUrl: String,
    // Optional<String>, not String: SmallRye's converter treats an empty value as null, so a plain
    // String with defaultValue = "" throws SRCFG00040 at boot rather than arriving blank. The
    // committed default IS empty (application.yaml: CLICKHOUSE_USER:), so this made the service
    // unbootable on its own defaults — invisible anywhere the env vars happen to be set.
    @ConfigProperty(name = "analytics.clickhouse-user")
    private val clickHouseUser: Optional<String>,
    @ConfigProperty(name = "analytics.clickhouse-password")
    private val clickHousePassword: Optional<String>,
    @ConfigProperty(name = "analytics.clickhouse-database", defaultValue = "openbank_analytics")
    private val database: String,
) : SegmentEvaluationPort {

    private val http: HttpClient = HttpClient.newHttpClient()

    override suspend fun count(segment: Segment): Long {
        val (where, params) = segment.toWhereClause()
        val sql = "SELECT count(DISTINCT aggregate_id) AS cohort_size FROM $database.silver_current_state " +
            "WHERE $where FORMAT JSONEachRow"
        val query = params.entries.joinToString("&") { (key, value) ->
            "param_$key=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)
        }
        val request = HttpRequest.newBuilder(URI.create("$clickHouseUrl?$query"))
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .header("Content-Type", "text/plain")
            .timeout(QUERY_TIMEOUT)
        clickHouseUser.filter { it.isNotBlank() }.ifPresent { request.header("X-ClickHouse-User", it) }
        clickHousePassword.filter { it.isNotBlank() }.ifPresent { request.header("X-ClickHouse-Key", it) }
        val response = withContext(Dispatchers.IO) {
            http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }
        check(response.statusCode() == HTTP_OK) { "segment count failed: ClickHouse ${response.statusCode()}" }
        return requireNotNull(COHORT_SIZE.find(response.body())?.groupValues?.get(1)) {
            "segment count contained no cohort_size"
        }.toLong()
    }

    /** ClickHouse enforces the page bound before its HTTP response reaches the JVM. */
    override suspend fun page(segment: Segment, after: UUID?, limit: Int): SegmentPage {
        require(limit in 1..SegmentPage.MAX_PAGE_SIZE) { "invalid segment page limit" }
        val (where, params) = segment.toWhereClause()
        val sql = buildString {
            append("SELECT DISTINCT aggregate_id FROM ").append(database).append(".silver_current_state")
            append(" WHERE (").append(where).append(")")
            if (after != null) append(" AND aggregate_id > {p_cursor:String}")
            append(" ORDER BY aggregate_id LIMIT {p_limit:UInt32} FORMAT JSONEachRow")
        }
        val bound = params + mapOf("p_limit" to limit) +
            (after?.let { mapOf("p_cursor" to it.toString()) } ?: emptyMap())
        val query = bound.entries.joinToString("&") { (key, value) ->
            "param_$key=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)
        }
        val builder = HttpRequest.newBuilder(URI.create("$clickHouseUrl?$query"))
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .header("Content-Type", "text/plain")
            .timeout(QUERY_TIMEOUT)
        clickHouseUser.filter { it.isNotBlank() }.ifPresent { builder.header("X-ClickHouse-User", it) }
        clickHousePassword.filter { it.isNotBlank() }.ifPresent { builder.header("X-ClickHouse-Key", it) }
        val response = withContext(Dispatchers.IO) {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        check(response.statusCode() == HTTP_OK) {
            "segment page failed: ClickHouse " + response.statusCode() + " — " +
                response.body().take(ERROR_PREVIEW_CHARS)
        }
        val ids = response.body().lineSequence().filter { it.isNotBlank() }.map { line ->
            UUID.fromString(
                requireNotNull(AGGREGATE_ID.find(line)?.groupValues?.get(1)) {
                    "segment page contained no aggregate_id"
                },
            )
        }.toList()
        check(ids.size <= limit && ids.distinct().size == ids.size) { "invalid segment page" }
        return SegmentPage(ids, ids.lastOrNull())
    }

    /**
     * One-party membership: the same generated WHERE clause plus an `aggregate_id` predicate.
     *
     * Uses the same typed rule conversion as count and page, so trigger membership follows the
     * same segment predicate as bulk enrolment.
     * `LIMIT 1` because the answer is a boolean; the party id travels as a bound parameter like
     * every other rule value, so it cannot become SQL.
     */
    override suspend fun matches(segment: Segment, partyId: UUID): Boolean {
        val (where, params) = segment.toWhereClause()
        val sql = buildString {
            append("SELECT 1 FROM ").append(database).append(".silver_current_state")
            append(" WHERE ").append(where)
            append(" AND aggregate_id = {p_party:String} LIMIT 1 FORMAT JSONEachRow")
        }
        val bound = params + mapOf("p_party" to partyId.toString())
        val query = bound.entries.joinToString("&") { (k, v) ->
            "param_$k=" + URLEncoder.encode(v.toString(), StandardCharsets.UTF_8)
        }
        val requestBuilder = HttpRequest.newBuilder(URI.create("$clickHouseUrl?$query"))
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .header("Content-Type", "text/plain")
        clickHouseUser.filter { it.isNotBlank() }.ifPresent { requestBuilder.header("X-ClickHouse-User", it) }
        clickHousePassword.filter { it.isNotBlank() }.ifPresent { requestBuilder.header("X-ClickHouse-Key", it) }

        val response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != HTTP_OK) {
            // Throw rather than answer false: a ClickHouse outage answered as "not a member" would
            // silently drop every triggered enrolment while looking like an empty segment. The
            // consumer decides what to do with a failure; this layer must not decide it by
            // returning the safe-looking value.
            error(
                "segment membership query failed (${response.statusCode()}): " +
                    response.body().take(ERROR_PREVIEW_CHARS),
            )
        }
        return response.body().isNotBlank()
    }

    companion object {
        private const val HTTP_OK = 200
        private val QUERY_TIMEOUT = Duration.ofSeconds(10)

        /** Enough of a ClickHouse error to identify it, short enough not to dump a page into a log. */
        private const val ERROR_PREVIEW_CHARS = 200
        private val AGGREGATE_ID = Regex("\"aggregate_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")
        private val COHORT_SIZE = Regex("\"cohort_size\"\\s*:\\s*\"?(\\d+)\"?")
    }
}
