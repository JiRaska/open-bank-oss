// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.it.TraceContractProfile
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
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of authorising a card transaction (the card money path,
 * ADR-0283 phase 1).
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real request and the real Postgres write; [com.openbank.cardprocessing.it.TraceContractRecorder]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * authorisation hold happened *inside* the request's trace. A hold that escaped the request
 * context — an un-propagated reactive hop, a fire-and-forget executor — still writes the row,
 * still answers 201, and still holds the money; it is invisible to every assertion this module
 * already had, but it lands in a trace of its own and this fails. On a money path that is the
 * difference between an audit that can prove "the hold happened as part of this authorisation"
 * and one that cannot.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.cardprocessing.it.PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
@TestSecurity(user = "card-processing-trace-it", roles = ["ROLE_OPERATOR"])
class CardAuthorizationTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `authorising a card transaction keeps the HTTP request and its hold INSERT in one trace`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "trace-contract-authorize-1")
            body(
                """
                {"cardId":"${CardAuthorizationOutboxIT.StubIssuer.KNOWN_CARD}","amountMinorUnits":25000,
                 "currencyCode":"CZK","channel":"CONTACTLESS","mcc":"5411","merchantName":"Potraviny",
                 "merchantCountry":"CZ","networkReference":"trace-contract-authorize-1"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/card-authorizations")
        } Then {
            statusCode(201)
        }

        // The client can receive 201 before the server's final span-end callback runs.
        // Wait for that span, without relaxing the trace/attribute assertions below.
        awaitServerSpan()
        exporter.contract()
            .requiresSpan("POST /api/v1/card-authorizations")
            .requiresAttribute("POST /api/v1/card-authorizations", "http.response.status_code")
            .requiresAttribute("INSERT card_authorizations", "db.sql.table")
            .requiresSameTrace("POST /api/v1/card-authorizations", "INSERT card_authorizations")
            .hasNoErrorSpan()
            .verifiedAs("card-authorization-hold")
    }

    private fun awaitServerSpan() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try {
                exporter.contract().requiresSpan("POST /api/v1/card-authorizations")
                return
            } catch (failure: AssertionError) {
                if (System.nanoTime() >= deadline) throw failure
            }
            Thread.sleep(25)
        }
    }
}
