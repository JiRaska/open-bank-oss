// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of registering a push device token.
 *
 * `POST /api/v1/devices` persists the token row the PUSH fan-out later targets (a fresh token —
 * this IT always sends one — is an INSERT into `device_tokens`). The spans asserted here are the
 * service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP
 * request and the real Postgres write; `TraceContractRecorder` only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * token happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop (`Panache.withTransaction` runs on its own Vert.x context if the
 * caller's does not propagate), a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap);
 * device registration is the write every PUSH notification later depends on, so it is the right
 * first contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(NotificationTraceContractIT.NotificationTraceProfile::class)
class NotificationTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off); the extra
     * key mirrors [ManagedTemplateResourceIT.AdvisoryAuthzProfile]: there is no OPA sidecar in a
     * test JVM and the `@Authorize` interceptor fails CLOSED (503) without one — the subject here
     * is the trace shape of the write, not the policy decision.
     */
    class NotificationTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_OPERATOR"])
    fun `registering a device keeps the request and its token INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "partyId": "${UUID.randomUUID()}",
                  "platform": "APNS",
                  "token": "trace-${UUID.randomUUID()}",
                  "appInstance": "trace-${UUID.randomUUID()}",
                  "appVersion": "1.0.0"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/devices")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/devices")
            .requiresAttribute("POST /api/v1/devices", "http.response.status_code")
            .requiresAttribute("INSERT device_tokens", "db.sql.table")
            .requiresSameTrace("POST /api/v1/devices", "INSERT device_tokens")
            .hasNoErrorSpan()
            .verifiedAs("notification-device-register")
    }
}
