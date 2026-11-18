// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the engagement-event append.
 *
 * A posted surface event is persisted to `engagement_event` (with its outbox row in the same
 * transaction, ADR-0220 D2) before the HTTP caller is answered ACCEPTED. The spans asserted here
 * are the service's OWN — Quarkus' server instrumentation produces the span for the real HTTP
 * request, and Quarkus' JDBC instrumentation produces the span for the real Postgres INSERT;
 * [RecordingSpanExporter] only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * event row happened *inside* the trace of the HTTP request that posted it. A persist that
 * escaped the request context — a fire-and-forget executor, a reactive hop that dropped the
 * context — still writes the row and still answers 202, is invisible to every persistence
 * assertion this module already had (SurfaceRestContractIT asserts state, not causality), and
 * lands in a trace of its own.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the generic surface-event append is the service's primary write path, so it is the
 * right first contract here.
 */
@QuarkusTest
@TestProfile(EngagementTraceContractIT.EngagementTraceProfile::class)
@TestSecurity(user = "edge@openbank.test", roles = ["ROLE_OPERATOR"])
class EngagementTraceContractIT {

    class EngagementTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry(NoKafkaResource::class.java),
            io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java),
        )

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // @Authorize on SurfaceResource — the deployed OPA sidecar is not in this stack.
            "openbank.authz.enforce" to "false",
        )
    }

    /** Same InMemory-channel twin as SurfaceRestContractIT — no broker in this contract's stack. */
    class NoKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector
            .switchOutgoingChannelsToInMemory("engagement-outbox-out") +
            InMemoryConnector.switchIncomingChannelsToInMemory(
                "lending-events-in",
                "party-events-in",
                "fraud-hold-events-in",
                "campaign-banner-placements-in",
            )

        override fun stop() = io.smallrye.reactive.messaging.memory.InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `posting a surface event keeps the request and its INSERT in one trace`() {
        val partyId = UUID.randomUUID()
        val contentId = "SAVINGS_RATE_BANNER"

        Given {
            contentType("application/json")
            body("""{"partyId":"$partyId","contentId":"$contentId","slot":"HOME_BANNER","type":"DISMISS"}""")
        } When {
            post("/api/v1/surfaces/events")
        } Then {
            statusCode(202)
        }

        awaitRow(partyId, contentId)

        exporter.contract()
            .requiresSpan("POST /api/v1/surfaces/events")
            .requiresSpan("INSERT engagement_event")
            .requiresAttribute("INSERT engagement_event", "db.sql.table")
            .requiresSameTrace("POST /api/v1/surfaces/events", "INSERT engagement_event")
            .hasNoErrorSpan()
            .verifiedAs("engagement-surface-event-append")
    }

    private fun awaitRow(partyId: UUID, contentId: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(partyId, contentId) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        org.assertj.core.api.Assertions.assertThat(count(partyId, contentId))
            .describedAs("engagement_event row for $partyId/$contentId").isEqualTo(1)
    }

    private fun count(partyId: UUID, contentId: String): Int = jdbc().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM engagement_event WHERE party_id = ? AND content_id = ?",
        ).use { query ->
            query.setObject(1, partyId)
            query.setString(2, contentId)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    private fun jdbc() = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )
}
