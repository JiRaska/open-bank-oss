// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.sink

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.DeadLetterRecord
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
 * End-to-end verification of the ClickHouse-native write adapters — [ClickHouseAnalyticsSink]
 * (ADR-0022 / ADR-0023 F1) and [ClickHouseDeadLetterSink] (ADR-0022 quarantine) — against a real
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
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("clickhouse/V11__referral_funnel.sql"),
                    "/docker-entrypoint-initdb.d/09-referral-funnel.sql",
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

    @Test
    fun `referral funnel deduplicates replayed reward facts by event id`() = runBlocking<Unit> {
        val programId = UUID.randomUUID().toString()
        val inviteId = UUID.randomUUID().toString()
        val qualified = referralEvent("Qualified", mapOf("programId" to programId, "inviteId" to inviteId))
        val requested = referralEvent(
            "RewardRequested",
            mapOf(
                "programId" to programId,
                "inviteId" to inviteId,
                "amount" to 500,
                "currency" to "CZK",
            ),
        )
        val accepted = referralEvent(
            "RewardOutcome",
            mapOf("programId" to programId, "inviteId" to inviteId, "outcome" to "ACCEPTED"),
        )

        // Replay the same request and outcome exactly as Kafka at-least-once delivery may do.
        sink().writeBatch(listOf(qualified, requested, requested, accepted, accepted))

        val row = reader.query(
            "SELECT qualified_invites, reward_requests, rewarded_invites, failed_rewards, " +
                "reversed_rewards, requested_reward_amount, currency " +
                "FROM $DB.gold_referral_funnel WHERE program_id = '$programId' FORMAT TabSeparated",
        ).trim()
        assertThat(row).isEqualTo("1\t1\t1\t0\t0\t500\tCZK")
    }

    // ---------------------------------------------------------------- dead-letter quarantine (#5761)

    private fun dlq() = ClickHouseDeadLetterSink().apply {
        clickhouse = reader
        mapper = this@ClickHouseAnalyticsSinkIT.mapper
    }

    /**
     * The assertion #5761 was missing: a quarantined record must be **readable back out of the
     * table**. The unit test can only prove the emitted JSONEachRow body is well shaped; only a real
     * server proves the column contract matches the shipped DDL and that the row is actually there.
     * Note what could not have caught this before — `LoggingDeadLetterSink.quarantine()` also returns
     * normally, so nothing short of reading the table distinguishes a durable write from a log line.
     */
    @Test
    fun `quarantine writes a row that is readable back from dead_letter_events`() = runBlocking<Unit> {
        val hash = "sha256:${UUID.randomUUID()}"
        val payload = """{"eventType":"account.opened","aggregateVersion":"""

        dlq().quarantine(
            DeadLetterRecord(
                contentHash = hash,
                rawPayload = payload,
                error = "JsonParseException: unexpected end of input",
                failedAt = Instant.parse("2026-05-30T12:00:00.000Z"),
            ),
        )

        val row = reader.query(
            "SELECT raw_payload, error, failed_at FROM $DB.dead_letter_events " +
                "WHERE content_hash = '$hash' FORMAT TabSeparated",
        ).trim()

        val cols = row.split("\t")
        // ClickHouse TabSeparated escapes the payload's quotes-free control chars only; the payload
        // itself round-trips verbatim, which is what makes the documented replay possible.
        assertThat(cols[0]).isEqualTo(payload)
        assertThat(cols[1]).contains("JsonParseException")
        assertThat(cols[2]).isEqualTo("2026-05-30 12:00:00.000")
    }

    /**
     * [DeadLetterRecord]'s KDoc promises the DLQ is idempotent on the content hash so an
     * at-least-once re-delivery of the same poison message does not inflate the queue — which the
     * readiness probe (`ANALYTICS_MAX_DEAD_LETTERS`) and the Grafana panel both count. That promise
     * is the table engine's, so only a real server can verify it.
     */
    @Test
    fun `re-delivered poison message collapses to one dead-letter row at FINAL`() = runBlocking<Unit> {
        val hash = "sha256:${UUID.randomUUID()}"
        val record = DeadLetterRecord(hash, "{oops", "boom", Instant.parse("2026-05-30T12:00:00.000Z"))

        dlq().quarantine(record)
        dlq().quarantine(record)

        val count = reader.query(
            "SELECT count() FROM $DB.dead_letter_events FINAL WHERE content_hash = '$hash' FORMAT TabSeparated",
        ).trim()
        assertThat(count).isEqualTo("1")
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

    private fun referralEvent(eventType: String, payload: Map<String, Any>): AnalyticsEnvelope = AnalyticsEnvelope(
        eventId = UUID.randomUUID(),
        aggregateType = "REFERRAL",
        aggregateId = UUID.randomUUID().toString(),
        aggregateVersion = 0,
        eventType = eventType,
        occurredAt = Instant.now(),
        sourceService = "openbank-referral-service",
        schemaVersion = 1,
        payload = payload,
    )
}
