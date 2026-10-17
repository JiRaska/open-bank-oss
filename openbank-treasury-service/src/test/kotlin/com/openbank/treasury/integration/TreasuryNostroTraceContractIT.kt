// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.treasury.it.PostgresTestResource
import com.openbank.treasury.it.TraceContractProfile
import com.openbank.treasury.nostro.NostroFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * The observable distributed shape of the nostro breaks listing.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres read; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the SELECT that actually answered the
 * listing happened *inside* the request's trace. A read that escaped the request context — an
 * un-propagated reactive hop, a client call on a separate executor — still returns rows and
 * still answers 200, is invisible to every presence assertion this module already had, and lands
 * in a trace of its own.
 *
 * The breaks listing is the right subject for treasury nostro reconciliation: an aged,
 * unresolved break is what a person acts on, and ADR-0315's guarantee is that a break is listed
 * for a person — a listing that lost its trace context corrupts every dependent alerting and
 * evidence observation, not just one response.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class TreasuryNostroTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `listing nostro breaks keeps the request and its nostro_breaks SELECT in one trace`() {
        When {
            get("/api/v1/treasury/nostro/${NostroFixtures.IBAN}/breaks")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("GET /api/v1/treasury/nostro/{account}/breaks")
            .requiresAttribute("GET /api/v1/treasury/nostro/{account}/breaks", "http.response.status_code")
            .requiresAttribute("SELECT nostro_breaks", "db.sql.table")
            .requiresSameTrace("GET /api/v1/treasury/nostro/{account}/breaks", "SELECT nostro_breaks")
            .hasNoErrorSpan()
            .verifiedAs("treasury-nostro-breaks-read")
    }
}
