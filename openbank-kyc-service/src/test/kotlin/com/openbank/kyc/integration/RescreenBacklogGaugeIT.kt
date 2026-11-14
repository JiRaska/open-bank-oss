// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.integration

import com.openbank.kyc.infrastructure.observability.RescreenBacklogGauge
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * The #15961 backlog gauges against real `kyc_screening_triggers` rows, sampled by the REAL
 * scheduler (a direct call would supply the Vert.x context a scheduled tick does not, #2148).
 *
 * The trigger loop is off here (the shipped default, and retry is off so nothing drains the
 * fixtures), so this also proves the disabled-with-pending series against the database.
 * Rows are written over plain JDBC with explicit `created_at` / `next_attempt_at`: the OPEN_FAILED
 * row was enqueued long ago but its next attempt is recent, so an age derived from
 * `next_attempt_at` instead of `created_at` reads young and fails the age assertion.
 */
@QuarkusTest
@TestProfile(RescreenBacklogGaugeIT.FastSampleProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
class RescreenBacklogGaugeIT {

    /** Literals only: a test profile loads in a different classloader from the test class. */
    class FastSampleProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.kyc.screening-triggers.backlog-sample-interval" to "1s",
            "openbank.kyc.screening-triggers.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
            "authz.enforce" to "false",
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var registry: MeterRegistry

    private fun insert(state: String, createdAgo: String, nextAttemptAgo: String?) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO kyc_screening_triggers
                    (trigger_id, party_id, trigger_type, publication_id, diff, state, attempts,
                     next_attempt_at, created_at)
                VALUES (?, ?, 'SANCTIONS_LIST_CHANGED', ?, '{}', ?, 1,
                        CASE WHEN ?::text IS NULL THEN NULL ELSE NOW() - ?::interval END,
                        NOW() - ?::interval)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, UUID.randomUUID())
                ps.setObject(3, UUID.randomUUID())
                ps.setString(4, state)
                ps.setString(5, nextAttemptAgo)
                ps.setString(6, nextAttemptAgo)
                ps.setString(7, createdAgo)
                ps.executeUpdate()
            }
        }
    }

    private fun read(name: String): Double = registry.find(name).tag("service", "kyc").gauge()?.value() ?: Double.NaN

    private fun awaitPending(expected: Double) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (read(RescreenBacklogGauge.METRIC_PENDING) != expected) {
            check(System.nanoTime() < deadline) {
                "pending gauge never reached $expected (last ${read(RescreenBacklogGauge.METRIC_PENDING)})"
            }
            Thread.sleep(200)
        }
    }

    @Test
    fun `gauges reflect the undrained rows in the table, not the opened history`() {
        dataSource.connection.use { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM kyc_screening_triggers") }
        }
        awaitPending(0.0)
        assertThat(read(RescreenBacklogGauge.METRIC_OLDEST_AGE)).isEqualTo(0.0)
        assertThat(read(RescreenBacklogGauge.METRIC_DISABLED_WITH_PENDING)).isEqualTo(0.0)

        insert(state = "OPEN_FAILED", createdAgo = "2 hours", nextAttemptAgo = "1 minute")
        insert(state = "PENDING", createdAgo = "10 minutes", nextAttemptAgo = null)
        insert(state = "OPENED", createdAgo = "5 hours", nextAttemptAgo = null)

        awaitPending(2.0)
        assertThat(read(RescreenBacklogGauge.METRIC_OLDEST_AGE)).isBetween(7_200.0, 7_200.0 + 120.0)
        assertThat(read(RescreenBacklogGauge.METRIC_DISABLED_WITH_PENDING)).isEqualTo(1.0)

        dataSource.connection.use { c ->
            c.createStatement().use {
                it.executeUpdate("UPDATE kyc_screening_triggers SET state = 'OPENED', resolved_at = NOW()")
            }
        }
        awaitPending(0.0)
        assertThat(read(RescreenBacklogGauge.METRIC_OLDEST_AGE)).isEqualTo(0.0)
        assertThat(read(RescreenBacklogGauge.METRIC_DISABLED_WITH_PENDING)).isEqualTo(0.0)
    }
}
