// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of the compliance-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * case happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still opens the case and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class AmlTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-compliance"),
            Claim(key = "preferred_username", value = "u-compliance"),
        ],
    )
    fun `opening an AML case keeps the request and its aml_cases INSERT in one trace`() {
        val party = UUID.randomUUID()

        Given {
            contentType("application/json")
            header("Idempotency-Key", "trace-${UUID.randomUUID()}")
            body(
                """{"partyId":"$party","customerReference":"REF-TRACE-1",""" +
                    """"screeningType":"MANUAL_INVESTIGATION","riskLevel":"MEDIUM",""" +
                    """"alertCode":"ALERT-TRACE"}""",
            )
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("partyId", equalTo(party.toString()))
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/aml/cases")
            .requiresAttribute("POST /api/v1/aml/cases", "http.response.status_code")
            .requiresAttribute("INSERT aml_cases", "db.sql.table")
            .requiresSameTrace("POST /api/v1/aml/cases", "INSERT aml_cases")
            .hasNoErrorSpan()
            .verifiedAs("aml-case-create")
    }
}
