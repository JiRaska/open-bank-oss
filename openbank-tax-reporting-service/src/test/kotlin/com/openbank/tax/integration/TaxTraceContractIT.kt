// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
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

/**
 * The observable distributed shape of assembling a GSFT statutory return.
 *
 * `POST /api/v1/statutory-returns/assemble` persists the assembled return (`statutory_return`)
 * only when every figure is sourced — fail-closed by name, never zero-filled (ADR-0337). The
 * spans asserted here are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation
 * produces them for the real HTTP request and the real Postgres write; `TraceContractRecorder`
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * return happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 200, is invisible to every assertion this module already had, and lands in a trace of
 * its own. For a statutory return the row IS the filing under assembly; a return no regulator
 * can tie back to the request that assembled it is a gap in the supervisory trail.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); GSFT assembly is the write the whole delivery lifecycle hangs off, so it is the right
 * first contract here.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_trace_it")],
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@TestProfile(TaxTraceContractIT.TaxTraceProfile::class)
class TaxTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder); the extra keys mirror
     * [GsftDeliveryDryRunIT.GsftProfile] — the bank code and the throwaway PGP key the delivery
     * path requires, plus the fixture snapshot alternative that stands in for the not-yet-sourced
     * depositor data. Literals only: a profile loads in a different classloader from the test
     * class.
     */
    class TaxTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "openbank.gsft.bank-code" to "6363",
            "openbank.gsft.pgp-public-key" to GsftDeliveryDryRunIT.TestKey.publicArmored(),
            "openbank.statutory-returns.deadline-check-every" to "1h",
            // Pin the sampler so the contract is hermetic: a parent-based sampler makes it a
            // function of an incoming traceparent's sampled flag the test does not control, and
            // an unsampled parent legitimately yields no spans. Sampling policy is the
            // deployment's concern, not the contract's.
            "quarkus.otel.traces.sampler" to "always_on",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(GsftDeliveryDryRunIT.FixtureSnapshots::class.java)
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_OPERATOR"])
    fun `assembling a return keeps the request and its return INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """{"catalogueId":"cz-gsft","returnCode":"GSFT-DIW-TEST","entityId":"company",""" +
                    """"period":"2026-11-02"}""",
            )
        } When {
            post("/api/v1/statutory-returns/assemble")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/statutory-returns/assemble")
            .requiresAttribute("POST /api/v1/statutory-returns/assemble", "http.response.status_code")
            .requiresAttribute("INSERT statutory_return", "db.sql.table")
            .requiresSameTrace("POST /api/v1/statutory-returns/assemble", "INSERT statutory_return")
            .hasNoErrorSpan()
            .verifiedAs("tax-statutory-return-assemble")
    }
}
