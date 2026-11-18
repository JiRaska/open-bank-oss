// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

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
 * The observable distributed shape of opening a KYC case.
 *
 * `POST /api/v1/kyc/cases` persists the case row and its `kyc_outbox` `KYC_CASE_OPENED` row in one
 * transaction (see [KycOutboxWriteIT] for why the outbox row is the event that activates a party
 * downstream, and why the dispatcher is pinned off here). The spans asserted here are the
 * service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP
 * request and the real Postgres write; `TraceContractRecorder` only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the case
 * happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had, and lands in a trace of its own.
 * For KYC that row is the start of a regulatory case file: a case no operator can tie back to the
 * request that opened it is a gap in the compliance trail itself.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap);
 * case opening is KYC's most important write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(KycTraceContractIT.KycTraceProfile::class)
class KycTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off); the extra
     * key mirrors [KycOutboxWriteIT.DispatcherOffResource]: there is no OPA sidecar in a test JVM
     * and the interceptor correctly fails CLOSED (503) without one — the subject here is the trace
     * shape of the write, not the policy decision ([KycSecurityTest] owns that).
     */
    class KycTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_ADMIN"])
    fun `opening a case keeps the request and its case INSERT in one trace`() {
        Given {
            contentType("application/json")
            body("""{"partyId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/kyc/cases")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/kyc/cases")
            .requiresAttribute("POST /api/v1/kyc/cases", "http.response.status_code")
            .requiresAttribute("INSERT kyc_cases", "db.sql.table")
            .requiresSameTrace("POST /api/v1/kyc/cases", "INSERT kyc_cases")
            .hasNoErrorSpan()
            .verifiedAs("kyc-case-open")
    }
}
