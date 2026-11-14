// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** What both evaluation ITs share: real Postgres, the REAL scheduler, counters seeded as slice 1 writes them. */
abstract class MonitoringEvaluationITSupport {
    @Inject
    lateinit var pool: PgPool

    protected fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    protected fun rows(sql: String, vararg args: Any): List<Row> = onEventLoop {
        pool.preparedQuery(sql).execute(Tuple.from(args.toList())).awaitSuspending().toList()
    }

    /** [count] below-mark CZK movements out, in the current bucket, exactly as the attribution sweep stores them. */
    protected fun seedBelowMarkOut(partyId: UUID, count: Long) {
        for (band in listOf("ALL", "SYN_CZK_BELOW_MARK")) {
            rows(
                """
                INSERT INTO aml_movement_counter
                  (party_id, currency, direction, rail, scheme_revision, bucket_start, band_code,
                   bucket_seconds, movement_count, updated_at)
                VALUES ($1, 'CZK', 'OUT', 'DOMESTIC', 1, date_trunc('hour', now()), $2, 3600, $3, now())
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                partyId,
                band,
                count,
            )
        }
    }

    protected fun evaluation(partyId: UUID): Row? = rows(
        "SELECT outcome, failure, pack_hash, input_hash FROM aml_monitoring_evaluation WHERE party_id = $1",
        partyId,
    )
        .singleOrNull()

    protected fun hits(partyId: UUID): List<Row> = rows(
        """
        SELECT scenario_code, disposition, case_id, detection_count, pack_hash, counter_snapshot, window_start
          FROM aml_monitoring_hit WHERE party_id = $1
        """.trimIndent(),
        partyId,
    )

    protected fun monitoringCases(partyId: UUID): List<Row> = rows(
        """
        SELECT case_id, alert_code, alert_detail, risk_level, status, idempotency_key FROM aml_cases
         WHERE party_id = $1 AND screening_type = 'TRANSACTION_MONITORING'
        """.trimIndent(),
        partyId,
    )

    protected fun await(what: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(ready()).describedAs(what).isTrue()
    }

    companion object {
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 250L

        fun profile(openCases: Boolean): Map<String, String> = mapOf(
            // `%test.quarkus.scheduler.enabled` is false for this service; these ITs switch it back on.
            "quarkus.scheduler.enabled" to "true",
            "openbank.aml.monitoring.evaluation.cron" to "*/2 * * * * ?",
            "openbank.aml.monitoring.evaluation.open-cases" to openCases.toString(),
            "openbank.aml.monitoring.attribution.enabled" to "false",
            "openbank.aml.party-resolution.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        val RESOURCES = listOf(
            QuarkusTestProfile.TestResourceEntry(PostgresRedisTestResource::class.java),
            QuarkusTestProfile.TestResourceEntry(InMemoryKafkaTestResource::class.java),
        )
    }
}

/**
 * ADR-0371 slice 2 in SHADOW, the default: the cron evaluates the pack over the counters and
 * records what it found. Nothing calls the job directly — a direct call would supply the Vert.x
 * context the scheduler has to supply itself.
 */
@QuarkusTest
@TestProfile(TransactionMonitoringEvaluationIT.ShadowProfile::class)
class TransactionMonitoringEvaluationIT : MonitoringEvaluationITSupport() {

    class ShadowProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = RESOURCES
        override fun getConfigOverrides(): Map<String, String> = profile(openCases = false)
    }

    @Test
    fun `in SHADOW a hit is recorded once per window with its evidence and no case is opened`() {
        seedBelowMarkOut(AT_MINIMUM, 3)
        seedBelowMarkOut(BELOW_MINIMUM, 2)

        await("the scheduled evaluation reached both parties") {
            evaluation(AT_MINIMUM) != null && evaluation(BELOW_MINIMUM) != null
        }
        // Several more ticks: a repeat detection must refresh the one hit, not add another.
        await("the hit was detected again by later ticks") {
            hits(AT_MINIMUM).any { it.getLong("detection_count") >= REPEATS }
        }

        val evaluated = evaluation(AT_MINIMUM)!!
        assertThat(evaluated.getString("outcome")).isEqualTo("FINDING")
        assertThat(evaluated.getString("failure")).isNull()
        assertThat(evaluated.getString("input_hash")).matches("[0-9a-f]{64}")

        val stored = hits(AT_MINIMUM)
        assertThat(stored.map { it.getOffsetDateTime("window_start") }.distinct()).hasSameSizeAs(stored)
        val hit = stored.first()
        assertThat(hit.getString("scenario_code")).isEqualTo("syn-structuring-czk-out")
        assertThat(hit.getString("disposition")).isEqualTo("SHADOW_RECORDED")
        assertThat(hit.getUUID("case_id")).isNull()
        assertThat(hit.getString("pack_hash")).isEqualTo(evaluated.getString("pack_hash"))
        assertThat(hit.getString("counter_snapshot"))
            .contains(
                "\"count\":3",
                "\"band\":\"SYN_CZK_BELOW_MARK\"",
                "\"currency\":\"CZK\"",
                hit.getString("pack_hash"),
            )

        val quiet = evaluation(BELOW_MINIMUM)!!
        assertThat(quiet.getString("outcome")).describedAs("rules did not match; not a clear").isEqualTo("NO_FINDING")
        assertThat(hits(BELOW_MINIMUM)).isEmpty()

        assertThat(monitoringCases(AT_MINIMUM)).describedAs("SHADOW opens no case").isEmpty()
        assertThat(monitoringCases(BELOW_MINIMUM)).isEmpty()
    }

    private companion object {
        const val REPEATS = 3L
        val AT_MINIMUM: UUID = UUID.fromString("00000000-0000-4000-8000-00000000d101")
        val BELOW_MINIMUM: UUID = UUID.fromString("00000000-0000-4000-8000-00000000d102")
    }
}

/** The same evaluation with case opening switched on: one case per party, scenario and window. */
@QuarkusTest
@TestProfile(TransactionMonitoringCaseOpeningIT.CasesProfile::class)
class TransactionMonitoringCaseOpeningIT : MonitoringEvaluationITSupport() {

    class CasesProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = RESOURCES
        override fun getConfigOverrides(): Map<String, String> = profile(openCases = true)
    }

    @Test
    fun `with case opening on a booked series opens exactly one monitoring case however often it is detected`() {
        seedBelowMarkOut(AT_MINIMUM, 3)
        seedBelowMarkOut(BELOW_MINIMUM, 2)

        await("a case was opened") { monitoringCases(AT_MINIMUM).isNotEmpty() }
        await("later ticks detected the same hit again") {
            hits(AT_MINIMUM).any { it.getLong("detection_count") >= REPEATS }
        }

        val stored = hits(AT_MINIMUM)
        val opened = monitoringCases(AT_MINIMUM)
        assertThat(opened).describedAs("one case per party, scenario and window").hasSameSizeAs(stored)
        assertThat(stored.map { it.getOffsetDateTime("window_start") }.distinct()).hasSameSizeAs(stored)
        val case = opened.first()
        val hit = stored.first { it.getUUID("case_id") == case.getUUID("case_id") }
        assertThat(hit.getString("disposition")).isEqualTo("CASE_OPENED")
        assertThat(case.getString("alert_code")).isEqualTo("syn-structuring-czk-out")
        assertThat(case.getString("risk_level")).isEqualTo("MEDIUM")
        assertThat(case.getString("status")).describedAs("opened, never auto-decided").isEqualTo("OPEN")
        assertThat(
            case.getString("alert_detail"),
        ).contains(hit.getString("pack_hash"), "\"count\":3", "syn-structuring-czk-out")
        assertThat(
            case.getString("idempotency_key"),
        ).describedAs("the reserved pack-monitoring key").startsWith("tm:$AT_MINIMUM:")

        assertThat(monitoringCases(BELOW_MINIMUM)).describedAs("below the minimum: no case").isEmpty()
    }

    private companion object {
        const val REPEATS = 3L
        val AT_MINIMUM: UUID = UUID.fromString("00000000-0000-4000-8000-00000000d201")
        val BELOW_MINIMUM: UUID = UUID.fromString("00000000-0000-4000-8000-00000000d202")
    }
}
