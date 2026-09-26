// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.sca.it.PostgresRedisTestResource
import com.openbank.sca.it.TraceContractProfile
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
 * The observable distributed shape of the money-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * aggregate happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had, and lands in a trace of its own.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class ScaTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `enrolling a device keeps the request and its device INSERT in one trace`() {
        val partyId = UUID.randomUUID()

        Given {
            contentType("application/json")
            body(
                """
                {
                  "credentialId": "cred-trace-${UUID.randomUUID()}",
                  "publicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE",
                  "algorithm": "ES256"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sca/parties/$partyId/devices")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/sca/parties/{partyId}/devices")
            .requiresAttribute("POST /api/v1/sca/parties/{partyId}/devices", "http.response.status_code")
            .requiresAttribute("INSERT sca_enrolled_devices", "db.sql.table")
            .requiresSameTrace("POST /api/v1/sca/parties/{partyId}/devices", "INSERT sca_enrolled_devices")
            .hasNoErrorSpan()
            .verifiedAs("sca-device-enroll")
    }
}
