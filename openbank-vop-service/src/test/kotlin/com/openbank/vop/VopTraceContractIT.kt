// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop

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
 * The observable distributed shape of a Verification of Payee check.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres insert; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that recorded the evidence of
 * this check happened *inside* the request's trace. A record that escaped the request context —
 * an un-propagated reactive hop, a transaction on a separate executor — still commits and still
 * answers 200, is invisible to every presence assertion this module already had, and lands in a
 * trace of its own.
 *
 * VoP evidence is the right subject: ADR-0171's legal value rests on every verification leaving
 * a tamper-evident row, and a row written outside the request's trace is exactly the class of
 * defect the evidence matrix exists to catch. The lookup downstreams are intentionally absent —
 * VoP fails open (ADR-0171 §3), so the service answers NO_DATA and still records the evidence.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class VopTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "petr.viewer", roles = ["ROLE_VIEWER"])
    fun `verifying a payee keeps the request and its vop_verification INSERT in one trace`() {
        Given {
            contentType("application/json")
            body("""{"creditorIban":"CZ6508000000192000145399","creditorName":"Ada Novak"}""")
        } When {
            post("/api/v1/vop/verify")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/vop/verify")
            .requiresAttribute("POST /api/v1/vop/verify", "http.response.status_code")
            // The reactive Hibernate client names its spans "INSERT vop_verification" — no INTO.
            .requiresAttribute("INSERT vop_verification", "db.sql.table")
            .requiresSameTrace("POST /api/v1/vop/verify", "INSERT vop_verification")
            .hasNoErrorSpan()
            .verifiedAs("vop-verify-payee")
    }
}
