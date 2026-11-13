// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresRedpandaTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The observable distributed shape of the book-of-record write below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERTs that actually persisted the
 * journal happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still
 * answers 201, is invisible to every presence assertion this module already had, and lands in a
 * trace of its own. For the ledger that is worse than for a read model: regulatory returns and
 * every downstream money path are built from these rows, so a journal post that lost its trace
 * context corrupts every dependent observation, not just one row.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap on every money-path service without one); the journal post is the ledger's most important
 * write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(TraceContractProfile::class)
class LedgerTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `posting a journal keeps the request and its journal INSERTs in one trace`() {
        // The deterministic posting accounts seeded by V3__ledger_governance.sql — the same
        // pair LedgerApiIT posts between.
        val glAssetId = "a0000000-0000-0000-0000-000000000001"
        val glLiabilityId = "a0000000-0000-0000-0000-000000000002"
        val today = LocalDate.now().toString()

        Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "transactionId": "${UUID.randomUUID()}",
                  "entryDate": "$today",
                  "valueDate": "$today",
                  "description": "trace contract posting",
                  "createdBy": "00000000-0000-0000-0000-000000000099",
                  "lines": [
                    {
                      "glAccountId": "$glAssetId",
                      "side": "DEBIT",
                      "amount": "100.00",
                      "currencyCode": "CZK",
                      "baseAmount": "100.00",
                      "baseCurrencyCode": "CZK"
                    },
                    {
                      "glAccountId": "$glLiabilityId",
                      "side": "CREDIT",
                      "amount": "100.00",
                      "currencyCode": "CZK",
                      "baseAmount": "100.00",
                      "baseCurrencyCode": "CZK"
                    }
                  ]
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(201)
            body("status", equalTo("POSTED"))
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/journals")
            .requiresAttribute("POST /api/v1/journals", "http.response.status_code")
            .requiresAttribute("INSERT journal_entries", "db.sql.table")
            .requiresAttribute("INSERT journal_lines", "db.sql.table")
            .requiresSameTrace("POST /api/v1/journals", "INSERT journal_entries")
            .requiresSameTrace("POST /api/v1/journals", "INSERT journal_lines")
            .hasNoErrorSpan()
            .verifiedAs("ledger-journal-post")
    }
}
