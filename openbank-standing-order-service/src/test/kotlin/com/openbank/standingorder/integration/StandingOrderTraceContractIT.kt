// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.standingorder.it.PostgresRedisTestResource
import com.openbank.standingorder.it.TraceContractProfile
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
 * The observable distributed shape of creating a standing order (money path).
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real request and the real Postgres write; [com.openbank.standingorder.it.TraceContractRecorder]
 * only adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would
 * receive. Nothing here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * standing order happened *inside* the request's trace. A write that escaped the request context —
 * an un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, and is invisible to every assertion this module already had, but it lands in a
 * trace of its own and this fails.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(TraceContractProfile::class)
class StandingOrderTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `creating a standing order keeps the HTTP request and its INSERT in one trace`() {
        val partyId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        Given {
            contentType("application/json")
            body(
                """{
                    "idempotencyKey": "trace-it-${UUID.randomUUID()}",
                    "partyId": "$partyId",
                    "debitAccountId": "$accountId",
                    "debtorIban": "DE89370400440532013000",
                    "debtorName": "Alice Debtor",
                    "creditorIban": "DE75512108001245126199",
                    "creditorName": "Bob Creditor",
                    "creditorBic": null,
                    "amountMinorUnits": 1000,
                    "currency": "EUR",
                    "frequency": "MONTHLY",
                    "paymentType": "SEPA_CREDIT",
                    "remittanceInfo": null,
                    "startDate": "2026-11-01",
                    "endDate": null
                }""",
            )
        } When {
            post("/api/v1/standing-orders")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/standing-orders")
            .requiresAttribute("POST /api/v1/standing-orders", "http.response.status_code")
            .requiresAttribute("INSERT standing_orders", "db.sql.table")
            .requiresSameTrace("POST /api/v1/standing-orders", "INSERT standing_orders")
            .hasNoErrorSpan()
            .verifiedAs("standing-order-create")
    }
}
