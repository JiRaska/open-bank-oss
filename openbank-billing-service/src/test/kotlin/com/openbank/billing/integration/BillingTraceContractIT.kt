// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.it.TraceContractProfile
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of posting a billing-cycle assessment (ADR-0143).
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real request and the real Postgres write; [com.openbank.billing.it.TraceContractRecorder]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing here is created by the test, so every assertion can fail.
 *
 * The account used below does not exist in the test profile's stubbed read-path adapters, so the
 * assessment is legitimately skipped (`ACCOUNT_CONTEXT_UNRESOLVED`, ADR-0143's fail-closed rule) —
 * but the skipped assessment is still persisted, which is exactly the write under contract: a
 * `billing_cycle_assessment` row the operator can audit. `requiresSameTrace` is the load-bearing
 * assertion: it says the INSERT that actually persisted the assessment happened *inside* the
 * request's trace. A write that escaped the request context — an un-propagated reactive hop, a
 * fire-and-forget executor — still writes the row and still answers 200, and is invisible to every
 * assertion this module already had, but it lands in a trace of its own and this fails.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.billing.it.PostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class BillingTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `posting a fee assessment keeps the HTTP request and its billing INSERT in one trace`() {
        val accountId = UUID.randomUUID()

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?cycleId=2026-10&accountId=$accountId&currency=CZK")
        } Then {
            statusCode(200)
        }

        // The client can receive 200 before the server's final span-end callback runs.
        // Wait for that span, without relaxing the trace/attribute assertions below.
        awaitServerSpan()
        exporter.contract()
            .requiresSpan("POST /api/v1/fees/post")
            .requiresAttribute("POST /api/v1/fees/post", "http.response.status_code")
            .requiresAttribute("INSERT billing_cycle_assessment", "db.sql.table")
            .requiresSameTrace("POST /api/v1/fees/post", "INSERT billing_cycle_assessment")
            .hasNoErrorSpan()
            .verifiedAs("billing-fee-post")
    }

    private fun awaitServerSpan() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try {
                exporter.contract().requiresSpan("POST /api/v1/fees/post")
                return
            } catch (failure: AssertionError) {
                if (System.nanoTime() >= deadline) throw failure
            }
            Thread.sleep(25)
        }
    }
}
