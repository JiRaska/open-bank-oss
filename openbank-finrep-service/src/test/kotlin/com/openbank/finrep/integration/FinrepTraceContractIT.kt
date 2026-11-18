// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.integration

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

/**
 * The observable distributed shape of opening a supervisory submission.
 *
 * `POST /api/v1/finrep/submissions` persists the DRAFT submission (`finrep_submission`) the
 * whole package → submit → acknowledge lifecycle hangs off. The spans asserted here are the
 * service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP
 * request and the real Postgres write; `TraceContractRecorder` only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * submission happened *inside* the request's trace. A write that escaped the request context —
 * an un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own. For a supervisory submission the row IS the return under preparation; a submission no
 * regulator can tie back to the request that opened it is a gap in the FINREP evidence trail.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); submission opening is the service's most important write, so it is the right first
 * contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(FinrepTraceContractIT.FinrepTraceProfile::class)
class FinrepTraceContractIT {

    /** The libs baseline gives this class its own boot (own recorder, dispatcher off). */
    class FinrepTraceProfile : TraceContractProfile()

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_OPERATOR"])
    fun `opening a submission keeps the request and its submission INSERT in one trace`() {
        Given {
            contentType("application/json")
            body("""{"templateId":"F01.01","referencePeriod":"2026-03-31","revision":1}""")
        } When {
            post("/api/v1/finrep/submissions")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/finrep/submissions")
            .requiresAttribute("POST /api/v1/finrep/submissions", "http.response.status_code")
            .requiresAttribute("INSERT finrep_submission", "db.sql.table")
            .requiresSameTrace("POST /api/v1/finrep/submissions", "INSERT finrep_submission")
            .hasNoErrorSpan()
            .verifiedAs("finrep-submission-open")
    }
}
