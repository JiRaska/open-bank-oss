// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.sanctions.it.PostgresTestResource
import com.openbank.sanctions.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
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
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class SanctionsTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `screening a subject keeps the request and its sanctions_checks INSERT in one trace`() {
        val created = RestAssured.given()
            .contentType("application/json")
            .body(
                """
                {
                  "idempotencyKey": "trace-contract-${UUID.randomUUID()}",
                  "entityType": "INDIVIDUAL",
                  "name": "Trace Contract Subject",
                  "aliases": [],
                  "dateOfBirth": null,
                  "nationality": null,
                  "identifiers": {},
                  "listTypes": null
                }
                """.trimIndent(),
            )
            .post("/api/v1/sanctions/screen")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/sanctions/screen: %s", created.body.asString())
            .isEqualTo(201)

        exporter.contract()
            .requiresSpan("POST /api/v1/sanctions/screen")
            .requiresAttribute("POST /api/v1/sanctions/screen", "http.response.status_code")
            .requiresAttribute("INSERT sanctions_checks", "db.sql.table")
            .requiresSameTrace("POST /api/v1/sanctions/screen", "INSERT sanctions_checks")
            .hasNoErrorSpan()
            .verifiedAs("sanctions-screen")
    }
}
