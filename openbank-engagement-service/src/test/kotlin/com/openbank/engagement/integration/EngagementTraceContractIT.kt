// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.integration

import com.openbank.engagement.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of the event recording below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the event row and the
 * INSERT of its outbox companion into `engagement_outbox` happened *inside* the request's
 * trace. [SurfaceRestContractIT] proves the HTTP surface validates and accepts the event; the
 * relational half of the ADR-0050 guarantee (event row and outbox row in one transaction) has
 * its own coverage in this module. The trace half is what a support engineer searching Tempo
 * for "why did this interaction reach the campaign projector" actually sees. A pair that
 * escaped the request context still answers 202 and is invisible to every assertion this
 * module already had, but lands in traces of its own — the event exists without an observable
 * cause.
 */
@QuarkusTest
@TestProfile(TraceContractProfile::class)
@TestSecurity(user = "trace-contract", roles = ["ROLE_OPERATOR"])
@QuarkusTestResource(EngagementTraceContractIT.NoKafkaResource::class)
@QuarkusTestResource(EngagementPostgresTestResource::class)
class EngagementTraceContractIT {

    class NoKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("engagement-outbox-out") +
                // Same incoming channels as SurfaceRestContractIT's resource — without this,
                // @QuarkusTest boot tries a real Kafka consumer connection with no broker here.
                InMemoryConnector.switchIncomingChannelsToInMemory(
                    "lending-events-in",
                    "party-events-in",
                    "fraud-hold-events-in",
                    "campaign-banner-placements-in",
                )

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `recording a surface event keeps the request, its event INSERT and its outbox INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """{"partyId":"${UUID.randomUUID()}","contentId":"SAVINGS_RATE_BANNER",""" +
                    """"slot":"HOME_BANNER","type":"DISMISS"}""",
            )
        } When {
            post("/api/v1/surfaces/events")
        } Then {
            statusCode(202)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/surfaces/events")
            .requiresAttribute("POST /api/v1/surfaces/events", "http.response.status_code")
            .requiresAttribute("INSERT engagement_event", "db.sql.table")
            .requiresAttribute("INSERT engagement_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/surfaces/events",
                "INSERT engagement_event",
                "INSERT engagement_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("engagement-record-event")
    }
}
