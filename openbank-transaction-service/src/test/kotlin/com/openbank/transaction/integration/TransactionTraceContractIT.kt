// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.transaction.it.PostgresRedpandaTestResource
import com.openbank.transaction.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The observable distributed shape of the money-path write below: posting a transaction.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres INSERT into the partitioned `transactions`
 * table; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can
 * read what an OTLP collector would receive. Nothing asserted here is created by the test, so
 * every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * transaction happened *inside* the request's trace. A write that escaped the request
 * context — an un-propagated reactive hop, a fire-and-forget executor — still writes the row
 * and still answers 201, is invisible to every assertion this module already had, and lands
 * in a trace of its own. Transaction-service is where money enters the platform; an
 * untraceable posting here is an untraceable customer balance everywhere downstream.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(TraceContractProfile::class)
class TransactionTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `posting a credit transaction keeps the request and its INSERT in one trace`() {
        val today = LocalDate.now().toString()

        Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "type": "CREDIT",
                  "targetAccountId": "${UUID.randomUUID()}",
                  "amount": "5000.00",
                  "currencyCode": "CZK",
                  "baseAmount": "5000.00",
                  "baseCurrencyCode": "CZK",
                  "description": "Trace contract posting",
                  "valueDate": "$today",
                  "bookingDate": "$today"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/transactions")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("type", equalTo("CREDIT"))
            body("status", equalTo("COMPLETED"))
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/transactions")
            .requiresAttribute("POST /api/v1/transactions", "http.response.status_code")
            .requiresAttribute("INSERT transactions", "db.sql.table")
            .requiresSameTrace("POST /api/v1/transactions", "INSERT transactions")
            .hasNoErrorSpan()
            .verifiedAs("transaction-post")
    }
}
