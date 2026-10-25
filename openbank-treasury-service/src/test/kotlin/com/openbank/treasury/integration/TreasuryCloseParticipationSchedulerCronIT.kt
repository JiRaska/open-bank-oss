// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.infrastructure.close.TreasuryCloseJobs
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the REAL `@Scheduled` dispatch of treasury's close-participation reporter (CLAUDE.md: a
 * direct call supplies the Vert.x context the scheduler does not), reading the evidence through the
 * real Postgres `treasury_close_job_run` (V21), `deal_journals` and `treasury_outbox` tables.
 *
 * `%test` gives the reporter a cron that never fires; the profile fires it every second with a
 * 3-day lookback. The accrual is ON (so it is due) but its first pass is an hour away, so only the
 * rows seeded here exist. The outbox dispatcher is off, so a seeded PENDING event stays pending until
 * the test releases it. Delivery goes to a recording sink. Literals only in the profile.
 */
@QuarkusTest
@TestProfile(TreasuryCloseParticipationSchedulerCronIT.EverySecondProfile::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class TreasuryCloseParticipationSchedulerCronIT {

    class EverySecondProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.treasury.accrual.enabled" to "true",
            "openbank.treasury.accrual.initial-delay" to "1h",
            "openbank.treasury.close.report-cron" to "* * * * * ?",
            "openbank.treasury.close.lookback-days" to "3",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(RecordingSink::class.java)
    }

    @Alternative
    @ApplicationScoped
    class RecordingSink : CloseParticipationSink {
        override fun deliver(participant: String, report: CloseParticipationReport): Uni<Unit> {
            delivered += participant to report
            return Uni.createFrom().item(Unit)
        }

        companion object {
            val delivered = CopyOnWriteArrayList<Pair<String, CloseParticipationReport>>()
        }
    }

    @Inject
    lateinit var clock: Clock

    @Test
    fun `the cron holds pending and in-flight days, reports refusals, and RAN once the event left`() {
        val today = AccountingClock.bank(clock).today()
        val pendingDay = today.minusDays(1)
        val inFlightDay = today.minusDays(2)
        val refusedDay = today.minusDays(3)
        val days = listOf(pendingDay, inFlightDay, refusedDay)
        val event = UUID.randomUUID()
        jdbc { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "DELETE FROM treasury_close_job_run WHERE business_date IN (${days.joinToString { "'$it'" }})",
                )
                st.executeUpdate(completed(pendingDay, refused = 0, reason = null))
                st.executeUpdate(
                    completed(refusedDay, refused = 1, reason = "1 ${TreasuryCloseJobs.REASON_DAY_CLOSED}"),
                )
                // A live pass for inFlightDay, started just now: well inside the derived stale threshold.
                st.executeUpdate(
                    "INSERT INTO treasury_close_job_run (business_date, job, items_processed, refused, " +
                        "completed_at, running, started_at) VALUES ('$inFlightDay', 'accrual', 1, 0, now(), TRUE, now())",
                )
                // The settlement event of a journal posted on pendingDay, not yet delivered.
                st.executeUpdate(
                    "INSERT INTO treasury_outbox " +
                        "(id, event_id, aggregate_id, event_type, payload, status, created_at) " +
                        "VALUES (nextval('treasury_outbox_seq'), '$event', '${UUID.randomUUID()}', " +
                        "'treasury.deal.settled.v1', '{}', 'PENDING', " +
                        "('$pendingDay 12:00'::timestamp AT TIME ZONE 'Europe/Prague'))",
                )
            }
        }

        awaitReport(refusedDay)
        // Several ticks have passed since the refused day was reported: the other two are still held.
        Thread.sleep(HOLD_MILLIS)
        assertThat(seen(refusedDay)).anySatisfy {
            assertThat(it.outcome).isEqualTo(CloseOutcome.REFUSED)
            assertThat(it.reason).contains("ACCOUNTING_DAY_CLOSED")
        }
        assertThat(seen(pendingDay)).describedAs("held while its journal event is in the outbox").isEmpty()
        assertThat(seen(inFlightDay)).describedAs("held while its pass is in flight").isEmpty()

        jdbc { c ->
            c.createStatement().use {
                it.executeUpdate("UPDATE treasury_outbox SET status = 'SENT' WHERE event_id = '$event'")
            }
        }
        awaitReport(pendingDay)
        assertThat(seen(pendingDay)).anySatisfy { assertThat(it.outcome).isEqualTo(CloseOutcome.RAN) }
        assertThat(seen(inFlightDay)).isEmpty()
    }

    @Inject
    lateinit var evidence: com.openbank.treasury.application.port.out.TreasuryCloseEvidencePort

    /** Real SQL: only a COMPLETE pass (refused = 0) counts, and the job/date parameters bind right. */
    @Test
    fun `the latest pass start ignores refused and in-flight passes`() {
        val base = LocalDate.parse("2091-03-01")
        jdbc { c ->
            c.createStatement().use { st ->
                st.executeUpdate("DELETE FROM treasury_close_job_run WHERE business_date >= '$base'")
                st.executeUpdate(pass(base, refused = 0, started = "2091-03-01 08:00"))
                st.executeUpdate(pass(base.plusDays(1), refused = 2, started = "2091-03-02 08:00"))
                st.executeUpdate(
                    "INSERT INTO treasury_close_job_run (business_date, job, running, started_at) " +
                        "VALUES ('${base.plusDays(2)}', 'accrual', TRUE, '2091-03-03 08:00+00')",
                )
                st.executeUpdate(pass(base.plusDays(3), refused = 0, started = "2091-03-04 08:00", job = "other"))
            }
        }
        val latest = io.quarkus.vertx.VertxContextSupport.subscribeAndAwait<java.time.Instant?> {
            evidence.latestCompletedPassStart(TreasuryCloseJobs.ACCRUAL, base)
        }
        assertThat(latest).isEqualTo(java.time.Instant.parse("2091-03-01T08:00:00Z"))
        val none = io.quarkus.vertx.VertxContextSupport.subscribeAndAwait<java.time.Instant?> {
            evidence.latestCompletedPassStart(TreasuryCloseJobs.ACCRUAL, base.plusDays(1))
        }
        assertThat(none).isNull()
        val cleanup = "DELETE FROM treasury_close_job_run WHERE business_date >= '$base'"
        jdbc { c -> c.createStatement().use { it.executeUpdate(cleanup) } }
    }

    private fun pass(day: LocalDate, refused: Int, started: String, job: String = "accrual"): String =
        "INSERT INTO treasury_close_job_run (business_date, job, items_processed, refused, completed_at, " +
            "running, started_at) VALUES ('$day', '$job', 1, $refused, now(), FALSE, '$started+00')"

    private fun completed(day: LocalDate, refused: Int, reason: String?): String =
        "INSERT INTO treasury_close_job_run (business_date, job, items_processed, refused, refusal_reason, " +
            "completed_at) VALUES ('$day', 'accrual', 1, $refused, ${reason?.let { "'$it'" } ?: "NULL"}, now())"

    private fun seen(day: LocalDate) = RecordingSink.delivered
        .filter { it.first == TreasuryCloseJobs.PARTICIPANT && it.second.businessDate == day }
        .map { it.second }

    private fun awaitReport(day: LocalDate) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (seen(day).isEmpty()) {
            check(System.nanoTime() < deadline) { "no report for $day within the wait" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 250L
        const val HOLD_MILLIS = 3_000L
    }
}
