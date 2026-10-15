// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.sepainstant.it.TraceContractProfile
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
import java.util.UUID

/**
 * The observable distributed shape of the money-path write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * payment happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still answers
 * 201, is invisible to every assertion this module already had, and lands in a trace of its own.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_trace_it")],
)
@TestProfile(TraceContractProfile::class)
class SctInstTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "operator-01", roles = ["ROLE_OPERATOR"])
    fun `submitting a sepa-instant payment keeps the request and its payments INSERT in one trace`() {
        val idempotencyKey = UUID.randomUUID().toString()

        Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(
                """
                {
                  "idempotencyKey": "$idempotencyKey",
                  "debtorAccountId": "${UUID.randomUUID()}",
                  "debtorIban": "CZ6508000000192000145399",
                  "debtorName": "Trace Debtor",
                  "creditorIban": "DE89370400440532013000",
                  "creditorName": "Trace Creditor",
                  "creditorBic": "COBADEFFXXX",
                  "amount": 99.99,
                  "currency": "EUR",
                  "remittanceInfo": "SCT Inst trace contract",
                  "endToEndId": "E2E-TRACE-${UUID.randomUUID()}"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/sepa-instant")
            .requiresAttribute("POST /api/v1/sepa-instant", "http.response.status_code")
            .requiresAttribute("INSERT sct_inst_payments", "db.sql.table")
            .requiresSameTrace("POST /api/v1/sepa-instant", "INSERT sct_inst_payments")
            .hasNoErrorSpan()
            .verifiedAs("sepa-instant-submit")
    }
}
