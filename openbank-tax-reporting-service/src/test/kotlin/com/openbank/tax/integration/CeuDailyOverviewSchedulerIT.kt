// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.testing.containers.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-0367 / #15292: the REAL Quarkus cron assembles a CEÚ overview through the reactive stack,
 * not a direct method call (a direct call would supply the Vert.x context the scheduler does not).
 *
 * The clock is pinned to Tuesday 2026-11-10 11:30 Prague, a CERTIS working day past the 11:00
 * alert cutoff, so the test does not depend on the day it runs. The register is seeded by a
 * test-only Flyway location before the scheduler starts: one real account, one opened under the
 * ADR-0252 taint, one held by a party-service SYNTHETIC party.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_ceu_it")],
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@TestProfile(CeuDailyOverviewSchedulerIT.CeuCronProfile::class)
class CeuDailyOverviewSchedulerIT {
    class CeuCronProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "quarkus.flyway.locations" to "db/migration,db/ceu-it-seed",
            "openbank.ceu.tick-every" to "1s",
            "openbank.ceu.reporting-start" to "2026-11-01",
            // Keep the unrelated deadline job quiet.
            "openbank.statutory-returns.deadline-check-every" to "1h",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(PinnedClock::class.java)
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    class PinnedClock {
        @Produces
        @Dependent
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-11-10T10:30:00Z"), ZoneOffset.UTC)
    }

    @Inject
    lateinit var registry: MeterRegistry

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `the cron assembles the working-day overview with synthetic customers excluded and counted`() {
        val row = awaitRow()
        assertThat(row).describedAs("the scheduler must assemble Monday's overview").isNotNull
        val (status, assembledBy, datapoints) = row!!
        assertThat(status).isEqualTo("ASSEMBLED")
        assertThat(assembledBy).isEqualTo("system:ceu-daily-overview")
        assertThat(datapoints).contains("\"changes_total\":\"1\"")
        assertThat(datapoints).contains("\"synthetic_accounts_dropped\":\"2\"")
        assertThat(datapoints).contains("\"initial_load\":\"1\"")
        assertThat(awaitLines()).singleElement().satisfies({
            assertThat(it).contains("1000000001/0800").doesNotContain("1000000002").doesNotContain("1000000003")
        })

        assertThat(gauge(WorkflowLivenessMetrics.SUCCESS_RECORDED, "ceu-daily-overview")).isEqualTo(1.0)
        assertThat(registry.find("openbank_ceu_overview_unfiled_past_cutoff").gauge()?.value())
            .describedAs("11:30 Prague and not yet filed: the missed-deadline signal must be up")
            .isEqualTo(1.0)
        assertThat(gauge("openbank_workflow_unconfigured", "ceu-daily-overview")).isEqualTo(0.0)

        approveSubmitAcknowledge()
    }

    /** A job-assembled overview is approved by a human, submitted and acknowledged from its STORED lines. */
    private fun approveSubmitAcknowledge() {
        assertThat(awaitRow()).isNotNull
        assertThat(awaitLines()).hasSize(1)
        val id = query(
            "select id from statutory_return where return_code = 'CEUSIFE10' and period_end = date '2026-11-09'",
        ) { it.getString(1) }.single()

        given().contentType(
            ContentType.JSON,
        ).post("/api/v1/statutory-returns/$id/approve").then().statusCode(200).body("status", equalTo("APPROVED"))
        // Submit recomputes the hash from the RELOADED return; it only passes if the lines were stored.
        given().contentType(ContentType.JSON).body("""{"reference":"SDAT-2026-11-10-001"}""")
            .post("/api/v1/statutory-returns/$id/submitted").then().statusCode(200).body("status", equalTo("SUBMITTED"))
        given().contentType(ContentType.JSON).body("""{"accepted":true,"reference":"CEU-ACK-7781"}""")
            .post("/api/v1/statutory-returns/$id/acknowledgement").then().statusCode(200)
            .body("status", equalTo("ACKNOWLEDGED"))
            .body("acknowledgement", equalTo("CEU-ACK-7781"))
        given().contentType(ContentType.JSON).body("""{"accepted":true}""")
            .post("/api/v1/statutory-returns/$id/acknowledgement").then().statusCode(400)
    }

    private fun gauge(name: String, workflow: String): Double? =
        registry.find(name).tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow).gauge()?.value()

    private fun awaitRow(): Triple<String, String, String>? {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            query(
                "select status, assembled_by, datapoints_json from statutory_return " +
                    "where return_code = 'CEUSIFE10' and period_end = date '2026-11-09'",
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }.firstOrNull()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun awaitLines(): List<String> {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var found = lines()
        while (found.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(250)
            found = lines()
        }
        return found
    }

    private fun lines(): List<String> = query(
        "select l.fields_json from statutory_return_line l join statutory_return r on r.id = l.return_id " +
            "where r.return_code = 'CEUSIFE10'",
    ) { it.getString(1) }

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } }
            }
        }
    }
}
