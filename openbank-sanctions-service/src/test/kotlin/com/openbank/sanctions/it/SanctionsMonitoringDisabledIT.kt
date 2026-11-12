// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.application.usecase.SanctionsMonitoringService
import com.openbank.sanctions.domain.model.MonitoringRunOutcome
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import com.openbank.sanctions.it.MonitoringFixture.Companion.ENTRY_PREFIX
import com.openbank.sanctions.it.MonitoringFixture.Companion.LISTED
import com.openbank.sanctions.it.MonitoringFixture.Companion.OWNER
import com.openbank.sanctions.it.MonitoringFixture.Companion.SUBJECT_PREFIX
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * The shipped default: `openbank.sanctions.monitoring.enabled` is false (ADR-0376).
 *
 * This class sets nothing, so it runs against `application.yaml` as committed. The registry row
 * is planted by SQL to build the strongest case: a subject IS registered, an entry matching it
 * IS ingested and queued — and still nothing is matched, raised or published.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@AsKybService
class SanctionsMonitoringDisabledIT {

    @Inject
    lateinit var monitoring: SanctionsMonitoringService

    @Inject
    lateinit var entries: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var db: MonitoringFixture

    @BeforeEach
    fun setUp() {
        db = MonitoringFixture(dataSource, entries)
        db.cleanUp()
    }

    @AfterEach
    fun tearDown() = db.cleanUp()

    private fun plant(subjectRef: String) = db.plant(OWNER, subjectRef, LISTED)

    @Test
    fun `with the flag off a registered subject matching an ingested entry raises no finding`() {
        plant("${SUBJECT_PREFIX}off")
        db.ingest("${ENTRY_PREFIX}off", LISTED)
        assertThat(db.count("sanctions_monitoring_pending")).describedAs("the change is queued").isEqualTo(1)

        assertThat(db.onEventLoop { monitoring.runDelta() }).isEqualTo(MonitoringRunOutcome.DISABLED)
        assertThat(db.onEventLoop { monitoring.runSweep() }).isEqualTo(MonitoringRunOutcome.DISABLED)

        assertThat(db.count("sanctions_monitoring_findings")).isZero()
        assertThat(db.count("sanctions_outbox", "event_type = 'MONITORED_SUBJECT_MATCHED'")).isZero()
        assertThat(db.count("sanctions_monitoring_pending")).describedAs("nothing consumed the queue").isEqualTo(1)
    }

    @Test
    fun `with the flag off registration is refused with 503 and stores nothing`() {
        val response = RestAssured.given().contentType("application/json")
            .body("""{"entityType":"INDIVIDUAL","name":"$LISTED"}""")
            .put("/api/v3/sanctions/monitored-subjects/${SUBJECT_PREFIX}refused")

        assertThat(response.statusCode).isEqualTo(503)
        assertThat(db.count("sanctions_monitored_subjects")).isZero()
    }

    @Test
    fun `with the flag off deregistration still deletes`() {
        plant("${SUBJECT_PREFIX}erase")

        val response = RestAssured.given().delete("/api/v3/sanctions/monitored-subjects/${SUBJECT_PREFIX}erase")

        assertThat(response.statusCode).isEqualTo(204)
        assertThat(db.count("sanctions_monitored_subjects")).isZero()
    }

    @Test
    fun `list ingestion queues nothing while the registry is empty`() {
        db.ingest("${ENTRY_PREFIX}unwatched", LISTED)

        assertThat(db.count("sanctions_change_journal", "external_id = '${ENTRY_PREFIX}unwatched'")).isEqualTo(1)
        assertThat(db.count("sanctions_monitoring_pending")).isZero()
    }
}
