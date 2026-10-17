// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.onboarding.it.OnboardingPostgresTestResource
import com.openbank.onboarding.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * The observable distributed shape of the onboarding listing below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres read; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the SELECT that actually answered the
 * listing happened *inside* the request's trace. A read that escaped the request context —
 * an un-propagated reactive hop, a client call on a separate executor — still returns rows and
 * still answers 200, is invisible to every presence assertion this module already had, and lands
 * in a trace of its own.
 *
 * The listing is the right subject for onboarding: the funnel view over `onboarding_records`
 * drives every KYC/compliance prioritisation decision, and the records carry PII — so a listing
 * read that lost its trace context breaks the observability of exactly the endpoint whose
 * leak potential (#8699) already justified its own guard.
 */
@QuarkusTest
@QuarkusTestResource(OnboardingPostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class OnboardingTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_VIEWER", "ROLE_OPERATOR"])
    fun `listing onboarding records keeps the request and its onboarding_records SELECT in one trace`() {
        When {
            get("/api/v1/onboarding/records")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("GET /api/v1/onboarding/records")
            .requiresAttribute("GET /api/v1/onboarding/records", "http.response.status_code")
            .requiresAttribute("SELECT onboarding_records", "db.sql.table")
            .requiresSameTrace("GET /api/v1/onboarding/records", "SELECT onboarding_records")
            .hasNoErrorSpan()
            .verifiedAs("onboarding-records-list")
    }
}
