// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
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
 * The observable distributed shape of reporting a DORA ICT incident.
 *
 * `POST /api/v1/ict-incidents` persists the incident row the DORA Art. 17 ICT register is read
 * from (`ict_incidents` — see [IctIncidentDurabilityIT] for why it must live in a row and not a
 * per-pod map). The spans asserted here are the service's OWN — Quarkus' OpenTelemetry
 * auto-instrumentation produces them for the real HTTP request and the real Postgres write;
 * `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * incident happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own. For the incident register the row IS the regulatory record; a report no operator can
 * tie back to the request that filed it is a gap in the DORA evidence trail.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); incident reporting is the register's most important write, so it is the right first
 * contract here.
 */
@QuarkusTest
@QuarkusTestResource(SecurityScannerTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(SecurityScannerTraceContractIT.SecurityScannerTraceProfile::class)
class SecurityScannerTraceContractIT {

    /** The libs baseline gives this class its own boot (own recorder, dispatcher off). */
    class SecurityScannerTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // Pin the sampler: with a parent-based sampler the contract becomes a function of
            // an incoming traceparent's sampled flag the test does not control, and an unsampled
            // parent legitimately yields no spans (observed as 'observed span names only: []').
            // The contract asserts span presence and context flow, not sampling policy.
            "quarkus.otel.traces.sampler" to "always_on",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("ict-incident-outbox-out") +
                mapOf("quarkus.kafka.devservices.enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_OPERATOR"])
    fun `reporting an incident keeps the request and its incident INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {"title":"trace-${UUID.randomUUID()}","description":"trace contract probe",
                 "category":"AVAILABILITY","severity":"P2_HIGH","affectedServices":["ledger"],
                 "detectedAt":null,"assignedTo":"trace-it"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/ict-incidents")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/ict-incidents")
            .requiresAttribute("POST /api/v1/ict-incidents", "http.response.status_code")
            .requiresAttribute("INSERT ict_incidents", "db.sql.table")
            .requiresSameTrace("POST /api/v1/ict-incidents", "INSERT ict_incidents")
            .hasNoErrorSpan()
            .verifiedAs("ict-incident-report")
    }
}
