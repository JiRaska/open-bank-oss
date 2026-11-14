// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import com.openbank.sanctions.it.MonitoringFixture.Companion.ENTRY_PREFIX
import com.openbank.sanctions.it.MonitoringFixture.Companion.LISTED
import com.openbank.sanctions.it.MonitoringFixture.Companion.OWNER
import com.openbank.sanctions.it.MonitoringFixture.Companion.SUBJECT_PREFIX
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import javax.sql.DataSource

/**
 * Drives the REAL `SanctionsMonitoringScheduler` cron with monitoring switched on, the way the
 * deployed pod runs it (ADR-0376).
 *
 * Every other monitoring IT calls `runDelta()` / `runSweep()` directly under `%test`, where the
 * scheduler is off — and a direct call supplies the Vert.x context the scheduler itself does not,
 * so those tests stay green against a plain `fun` wrapping `runBlocking` (#2148, #2187). Here
 * nothing calls the service: a listing ingested after registration must turn into a finding,
 * and both liveness heartbeats must record a success, on the scheduler's own threads.
 *
 * The profile enables the scheduler, so the list-refresh tick would also run; every list is
 * disabled for the duration so no tick can reach a live feed.
 *
 * Literals only in the profile: it loads in a different classloader from the test class.
 */
@QuarkusTest
@TestProfile(SanctionsMonitoringCronIT.CronProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsMonitoringCronIT {

    class CronProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.sanctions.monitoring.enabled" to "true",
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var entries: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var registry: MeterRegistry

    private val db by lazy { MonitoringFixture(dataSource, entries) }

    @BeforeEach
    fun quietFeeds() {
        db.sql("UPDATE sanctions_lists SET enabled = false")
        db.cleanUp()
    }

    @AfterEach
    fun restore() {
        db.cleanUp()
        db.sql("UPDATE sanctions_lists SET enabled = true")
    }

    @Test
    fun `the scheduled delta turns a new listing of a registered subject into a finding`() {
        val subject = "${SUBJECT_PREFIX}cron"
        db.plant(OWNER, subject, LISTED)
        db.ingest("${ENTRY_PREFIX}cron", LISTED)

        val subjectFindings =
            "sanctions_monitoring_findings f JOIN sanctions_monitored_subjects s ON s.id = f.subject_id"
        val deadline = System.nanoTime() + Duration.ofSeconds(CRON_WAIT_SECONDS).toNanos()
        while (System.nanoTime() < deadline && !settled(subjectFindings, subject)) {
            Thread.sleep(POLL_MILLIS)
        }

        assertThat(db.count(subjectFindings, "s.subject_ref = '$subject'"))
            .describedAs("finding raised by the scheduled delta").isEqualTo(1L)
        assertThat(successRecorded(DELTA)).describedAs("delta heartbeat").isEqualTo(1.0)
        assertThat(successRecorded(SWEEP)).describedAs("sweep heartbeat").isEqualTo(1.0)
    }

    private fun settled(subjectFindings: String, subject: String): Boolean =
        db.count(subjectFindings, "s.subject_ref = '$subject'") == 1L &&
            successRecorded(DELTA) == 1.0 &&
            successRecorded(SWEEP) == 1.0

    private fun successRecorded(workflow: String): Double? = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow)
        .gauge()
        ?.value()

    private companion object {
        // delta: delayed 45s + every 60s; sweep: delayed 75s. Two ticks of headroom.
        const val CRON_WAIT_SECONDS = 200L
        const val POLL_MILLIS = 2000L
        const val DELTA = "sanctions-monitoring-delta"
        const val SWEEP = "sanctions-monitoring-sweep"
    }
}
