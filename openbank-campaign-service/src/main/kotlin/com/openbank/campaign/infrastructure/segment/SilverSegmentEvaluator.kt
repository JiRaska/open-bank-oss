// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.segment

import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.domain.model.Segment
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
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

    private val log = Logger.getLogger(SilverSegmentEvaluator::class.java)
    private val http: HttpClient = HttpClient.newHttpClient()

    override suspend fun evaluate(segment: Segment): List<UUID> {
        val (where, params) = segment.toWhereClause()
        val sql = buildString {
            append("SELECT DISTINCT aggregate_id FROM ").append(database).append(".silver_current_state")
            append(" WHERE ").append(where)
            append(" FORMAT JSONEachRow")
        }
        // Bind values travel as `?param_<name>=` on the URL — ClickHouse's only supported form for
        // query parameters over HTTP. They used to be sent as `X-ClickHouse-Parameter-<name>`
        // headers, which ClickHouse ignores entirely, so every evaluation died with
        // `Code: 456 ... Substitution 'p0_status' is not set` (#2749). Still parameters, not
        // interpolation: the values are URL-encoded here and substituted by ClickHouse, so a rule
        // value cannot become SQL.
        val query = params.entries.joinToString("&") { (k, v) ->
            "param_$k=" + URLEncoder.encode(v.toString(), StandardCharsets.UTF_8)
        }
        val uri = URI.create(if (query.isEmpty()) clickHouseUrl else "$clickHouseUrl?$query")
        val requestBuilder = HttpRequest.newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .header("Content-Type", "text/plain")
        clickHouseUser.filter { it.isNotBlank() }.ifPresent { requestBuilder.header("X-ClickHouse-User", it) }
        clickHousePassword.filter { it.isNotBlank() }.ifPresent { requestBuilder.header("X-ClickHouse-Key", it) }

        val response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != HTTP_OK) {
            log.errorf("ClickHouse segment evaluation failed (%d): %s", response.statusCode(), response.body())
            // ANY non-200 throws. An earlier version of this only threw on 4xx, reasoning that a
            // rejected query is a bug while a 5xx is an outage worth failing closed on. That split
            // does not survive contact with ClickHouse: it answers **500** for SQL it cannot
            // execute, so the exact defect the split existed to surface — an unbound parameter,
            // `Code: 456 ... Substitution is not set` — came back as 5xx and was swallowed into an
            // empty cohort anyway. "Nobody matched" and "the query never ran" must not look alike,
            // and no status-code split can tell them apart here.
            throw IllegalStateException(
                "segment ${segment.name}@${segment.version} could not be evaluated: " +
                    "ClickHouse returned ${response.statusCode()} — ${response.body().trim()}",
            )
        }
        return response.body().lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching {
                    UUID.fromString(AGGREGATE_ID.find(line)?.groupValues?.get(1))
                }.getOrNull()
            }
            .toList()
    }

    companion object {
        private const val HTTP_OK = 200
        private val AGGREGATE_ID = Regex("\"aggregate_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")
    }
}
