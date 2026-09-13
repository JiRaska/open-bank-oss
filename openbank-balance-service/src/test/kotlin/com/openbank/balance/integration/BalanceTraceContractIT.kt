// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import com.openbank.balance.it.TraceContractProfile
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

/**
 * The observable distributed shape of opening a currency pocket (money path, ADR-0024).
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real request and the real Postgres write; [com.openbank.balance.it.TraceContractRecorder]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * pocket happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, and is invisible to every assertion this module already had, but it lands in a trace of its
 * own and this fails.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
@TestProfile(TraceContractProfile::class)
class BalanceTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `initializing a pocket keeps the HTTP request and its balance INSERT in one trace`() {
        val accountId = UUID.randomUUID()

        Given {
            contentType("application/json")
            body("""{"currency": "CZK", "initialAmount": "1000.00"}""")
        } When {
            post("/api/v1/balances/$accountId/initialize")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/balances/{accountId}/initialize")
            .requiresAttribute("POST /api/v1/balances/{accountId}/initialize", "http.response.status_code")
            .requiresAttribute("INSERT balances", "db.sql.table")
            .requiresSameTrace("POST /api/v1/balances/{accountId}/initialize", "INSERT balances")
            .hasNoErrorSpan()
            .verifiedAs("balance-pocket-initialize")
    }
}
