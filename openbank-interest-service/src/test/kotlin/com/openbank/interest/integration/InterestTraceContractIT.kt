// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.integration

import com.openbank.interest.it.TraceContractProfile
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
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
 * The observable distributed shape of the rate-config write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * rate config happened *inside* the request's trace. A write that escaped the request context —
 * an un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every presence assertion this module already had, and lands in a
 * trace of its own.
 *
 * Rate config is the right subject for interest: every downstream money path (accrual,
 * capitalization, withholding) reads these rows, so a config write that lost its trace context
 * corrupts every dependent observation, not just one row.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
@TestProfile(TraceContractProfile::class)
class InterestTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `creating a rate config keeps the request and its rate-config INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "productId": "TRACE_${UUID.randomUUID().toString().take(8)}",
                  "currency": "CZK",
                  "annualRate": 0.365000,
                  "effectiveFrom": "2026-01-01",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/interest/rates")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/interest/rates")
            .requiresAttribute("POST /api/v1/interest/rates", "http.response.status_code")
            .requiresAttribute("INSERT interest_rate_configs", "db.sql.table")
            .requiresSameTrace("POST /api/v1/interest/rates", "INSERT interest_rate_configs")
            .hasNoErrorSpan()
            .verifiedAs("interest-rate-config-create")
    }
}
