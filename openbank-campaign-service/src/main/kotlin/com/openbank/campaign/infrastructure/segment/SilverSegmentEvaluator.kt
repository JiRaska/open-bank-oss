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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    @ConfigProperty(name = "analytics.clickhouse-user", defaultValue = "")
    private val clickHouseUser: String,
    @ConfigProperty(name = "analytics.clickhouse-password", defaultValue = "")
    private val clickHousePassword: String,
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
        val requestBuilder = HttpRequest.newBuilder(URI.create(clickHouseUrl))
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .header("Content-Type", "text/plain")
        if (clickHouseUser.isNotBlank()) requestBuilder.header("X-ClickHouse-User", clickHouseUser)
        if (clickHousePassword.isNotBlank()) requestBuilder.header("X-ClickHouse-Key", clickHousePassword)
        params.forEach { (k, v) -> requestBuilder.header("X-ClickHouse-Parameter-$k", v.toString()) }

        val response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != HTTP_OK) {
            // Fail closed: an unreachable analytics layer means an empty cohort, never a guessed one.
            log.errorf("ClickHouse segment evaluation failed (%d): %s", response.statusCode(), response.body())
            return emptyList()
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
