// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of a unit order.
 *
 * `POST /api/v2/contracts/{contractId}/orders` places a participant's unit order by INSERTing the
 * `unit_orders` row — the pending instruction NAV settlement later executes, so the row IS the
 * pension-fund money write at request time (the reservation/dedup properties of the outgoing leg
 * are pinned separately by [OutgoingReservationIT]). The spans asserted here are the service's
 * OWN: Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP request and
 * the real Postgres writes; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a
 * contract can read what an OTLP collector would receive. Nothing asserted here is created by
 * the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the order row happened *inside* the
 * request's trace. A write that escaped the request context — an un-propagated reactive hop, a
 * fire-and-forget executor — still writes the row and still answers 202, is invisible to every
 * presence assertion this module already had, and lands in a trace of its own: a unit reservation
 * no operator can reconstruct from telemetry, on participant savings.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); the unit order is this service's money-path entry
 * point, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.pensionfund.it.PostgresTestResource::class)
@TestProfile(PensionFundTraceContractIT.PensionFundTraceProfile::class)
@TestSecurity(user = "trace-it-operator", roles = ["ROLE_OPERATOR", "ROLE_API"])
class PensionFundTraceContractIT {

    /** The plain libs baseline suffices: this module's `%test` already pins `test-port: 0`. */
    class PensionFundTraceProfile : TraceContractProfile()

    @Inject
    lateinit var exporter: RecordingSpanExporter

    /** Arrangement only: an order needs a fund to reference (same shape as OutgoingReservationIT). */
    private fun createFund(): UUID {
        val isin = "TR" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase() + "0"
        val id: String = Given {
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType("application/json")
            body(
                """{"name":"Trace test","isin":"$isin","lei":"315700ABCDEF12345678",
                    "depositaryReference":"TEST-DEP","custodyAccountReference":"TEST-CUST",
                    "currency":"CZK","riskClass":3,"mandatoryConservative":false,
                    "productRevisionId":"00000000-0000-0000-0000-000000000001","launchNavPerUnit":1}
                """.trimIndent(),
            )
        } When {
            post("/api/v2/funds")
        } Then {
            statusCode(201)
        } Extract {
            path("id")
        }
        return UUID.fromString(id)
    }

    @Test
    fun `placing a unit order keeps the request and the money INSERT in one trace`() {
        val fund = createFund()
        val contract = UUID.randomUUID()

        Given {
            contentType("application/json")
            header("Idempotency-Key", "trace-it-${System.nanoTime()}")
            body("""{"fundId":"$fund","type":"SUBSCRIBE","amount":1000.00}""")
        } When {
            post("/api/v2/contracts/$contract/orders")
        } Then {
            statusCode(202)
            body("id", notNullValue())
        }

        exporter.contract()
            .requiresSpan("POST /api/v2/contracts/{contractId}/orders")
            .requiresAttribute("POST /api/v2/contracts/{contractId}/orders", "http.response.status_code")
            .requiresAttribute("INSERT unit_orders", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v2/contracts/{contractId}/orders",
                "INSERT unit_orders",
            )
            .hasNoErrorSpan()
            .verifiedAs("pension-fund-unit-order")
    }
}
