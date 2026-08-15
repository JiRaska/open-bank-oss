// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.sink

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import com.openbank.analytics.infrastructure.support.KGenericContainer
import com.openbank.libs.analytics.AnalyticsEnvelope
import com.openbank.libs.analytics.AnalyticsIntegrity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * End-to-end verification of [ClickHouseAnalyticsSink] (ADR-0022 / ADR-0023 F1) against a real
 * ClickHouse server. Unlike the unit test (which stubs the [ClickHouseAnalyticsSink.send] seam), this
 * exercises the **actual** HTTP insert path: the JDK HttpClient request, the JSONEachRow encoding, the
 * `X-ClickHouse-User/Key` auth headers, and the bronze schema from the real production DDL — all of
 * which the seam-level unit test cannot prove.
 *
 * The container mounts the same `V1__analytics_bronze_silver.sql` shipped to operators, started with
 * the same `CLICKHOUSE_DB/USER/PASSWORD` the dev `docker-compose.yml` uses, so a green run also proves
 * the DDL is valid and the adapter's column contract matches it. The test self-skips when Docker is
 * absent, so the offline build is unaffected.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ClickHouseAnalyticsSinkIT {

    companion object {
        private const val DB = "openbank_analytics"
        private const val USER = "analytics"
        private const val PASSWORD = "it_clickhouse_pw"

        @Container
        @JvmStatic
        private val clickhouse: KGenericContainer =
            KGenericContainer("clickhouse/clickhouse-server:24.3-alpine")
                .withEnv("CLICKHOUSE_DB", DB)
                .withEnv("CLICKHOUSE_USER", USER)
                .withEnv("CLICKHOUSE_PASSWORD", PASSWORD)
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("clickhouse/V1__analytics_bronze_silver.sql"),
                    "/docker-entrypoint-initdb.d/01-analytics.sql",
                )
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("clickhouse/V6__campaign_engagement.sql"),
                    "/docker-entrypoint-initdb.d/06-campaign-engagement.sql",
                )
                .withExposedPorts(8123)
                // The mounted DDL triggers ClickHouse's initdb flow (temp server → run SQL → shut down →
                // real server), far heavier than a plain boot. On a contended host (many other containers
                // competing for cores) that double-start can take minutes, so widen the probe window well
                // past the default 60s; an idle box still clears /ping in well under a minute.
                .waitingFor(Wait.forHttp("/ping").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))

        private fun baseUrl() = "http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}"

        /** Verification client (real HTTP reads), configured against the container. */
        private val reader: ClickHouseClient by lazy {
            ClickHouseClient().apply {
                url = baseUrl()
                database = DB
                username = USER
                password = Optional.of(PASSWORD)
            }
        }

        @BeforeAll
        @JvmStatic
        fun awaitSchema() {
            // /ping can answer before the init SQL has finished, so poll until bronze_events is queryable.
            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val ok = runCatching {
                    runBlocking { reader.query("SELECT count() FROM $DB.bronze_events FORMAT TabSeparated") }
                }.isSuccess
                if (ok) return
                check(System.currentTimeMillis() < deadline) { "bronze_events never became queryable" }
                Thread.sleep(500)
            }
        }
    }

    private val mapper = ObjectMapper()

    private fun sink() = ClickHouseAnalyticsSink().apply {
        url = baseUrl()
        database = DB
        username = USER
        password = Optional.of(PASSWORD)
        mapper = this@ClickHouseAnalyticsSinkIT.mapper
    }

    @Test
    fun `writeBatch inserts a bronze row with the F1 record hash over the real HTTP path`() = runBlocking<Unit> {
        val env = AnalyticsEnvelope(
            eventId = UUID.randomUUID(),
            aggregateType = "ACCOUNT",
            aggregateId = "acc-${UUID.randomUUID()}",
            aggregateVersion = 3,
            eventType = "account.account.opened",
            occurredAt = Instant.parse("2026-05-30T10:15:30.123Z"),
            sourceService = "openbank-account-service",
            schemaVersion = 1,
            actorId = "operator-7",
            actorType = "ROLE_OPERATOR",
            traceId = "trace-abc",
            payload = mapOf("currencyCode" to "CZK", "status" to "ACTIVE"),
        )

        sink().writeBatch(listOf(env))

        val row = reader.query(
            "SELECT aggregate_type, aggregate_version, event_type, record_hash, payload " +
                "FROM $DB.bronze_events WHERE event_id = '${env.eventId}' FORMAT TabSeparated",
        ).trim()

        val cols = row.split("\t")
        assertThat(cols[0]).isEqualTo("ACCOUNT")
        assertThat(cols[1]).isEqualTo("3")
        assertThat(cols[2]).isEqualTo("account.account.opened")
        // The persisted F1 tamper-evidence digest must equal the canonical hash recomputed from the envelope.
        assertThat(cols[3]).isEqualTo(AnalyticsIntegrity.recordHash(env))
        assertThat(cols[4]).contains("CZK").contains("ACTIVE")
    }

    @Test
    fun `at-least-once duplicate of the same event collapses to one row at FINAL`() = runBlocking<Unit> {
        val env = AnalyticsEnvelope(
            eventId = UUID.randomUUID(),
            aggregateType = "PARTY",
            aggregateId = "party-${UUID.randomUUID()}",
            aggregateVersion = 1,
            eventType = "party.party.created",
            occurredAt = Instant.parse("2026-05-30T11:00:00.000Z"),
            sourceService = "openbank-party-service",
            schemaVersion = 1,
        )

        // Same event delivered twice (Kafka at-least-once). ReplacingMergeTree keyed by
        // (aggregate_type, aggregate_id, event_id) must collapse them under FINAL.
        sink().writeBatch(listOf(env))
        sink().writeBatch(listOf(env))

        val count = reader.query(
            "SELECT count() FROM $DB.bronze_events FINAL WHERE event_id = '${env.eventId}' FORMAT TabSeparated",
        ).trim()
        assertThat(count).isEqualTo("1")
    }

    @Test
    fun `campaign mart separates app observation types without exposing party data`() = runBlocking<Unit> {
        val campaignId = UUID.randomUUID().toString()
        val commonPayload = mapOf(
            "campaignId" to campaignId,
            "stepOrder" to 0,
            "channel" to "PUSH",
        )
        val impression = campaignEvent("EngagementEvent.IMPRESSION", commonPayload)
        val click = campaignEvent("EngagementEvent.CLICK", commonPayload)

        sink().writeBatch(listOf(impression, click))

        val row = reader.query(
            "SELECT impressions, clicks, dismissals " +
                "FROM $DB.gold_campaign_engagement WHERE campaign_id = '$campaignId' FORMAT TabSeparated",
        ).trim()

        assertThat(row).isEqualTo("1\t1\t0")
        val columns = reader.query(
            "SELECT name FROM system.columns WHERE database = '$DB' " +
                "AND table = 'gold_campaign_engagement' ORDER BY name FORMAT TabSeparated",
        )
        assertThat(columns).doesNotContain("party_id").doesNotContain("interaction_ref")
    }

    private fun campaignEvent(eventType: String, payload: Map<String, Any>): AnalyticsEnvelope = AnalyticsEnvelope(
        eventId = UUID.randomUUID(),
        aggregateType = "ENGAGEMENT",
        aggregateId = UUID.randomUUID().toString(),
        aggregateVersion = 0,
        eventType = eventType,
        occurredAt = Instant.now(),
        sourceService = "openbank-engagement-service",
        schemaVersion = 1,
        payload = payload,
    )
}
