// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.integration

import com.openbank.anacredit.it.PostgresRedpandaTestResource
import com.openbank.anacredit.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * The observable distributed shape of an exposure registration.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the exposure row into
 * `credit_exposures` happened *inside* the registration request's trace. A row that escaped
 * the request context (written by a follow-up job, a detached session) still answers 201 and
 * passes every assertion this module already had, but lands in traces of its own — the
 * exposure exists in the regulatory feed without an observable cause.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(TraceContractProfile::class)
class AnaCreditTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_ADMIN"])
    fun `registering an exposure keeps the request and its exposure INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """{"instrumentId":"OD-TRACE-1","debtorId":"LE-TRACE-ACME",""" +
                    """"debtorType":"LEGAL_ENTITY","currency":"EUR","committedAmount":40000,""" +
                    """"drawnAmount":12000,"committedAmountEur":40000,"originationDate":"2025-06-01"}""",
            )
        } When {
            post("/api/v1/anacredit/exposures")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/anacredit/exposures")
            .requiresAttribute("POST /api/v1/anacredit/exposures", "http.response.status_code")
            .requiresAttribute("INSERT credit_exposures", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/anacredit/exposures",
                "INSERT credit_exposures",
            )
            .hasNoErrorSpan()
            .verifiedAs("anacredit-register-exposure")
    }
}
