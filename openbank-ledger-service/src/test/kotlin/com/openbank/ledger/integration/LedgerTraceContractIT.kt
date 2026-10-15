// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresRedpandaTestResource
import com.openbank.ledger.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
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
 * The observable distributed shape of the money-path write below: posting a balanced journal.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres INSERT into the partitioned
 * `journal_entries` table; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a
 * contract can read what an OTLP collector would receive. Nothing asserted here is created by
 * the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * journal happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace
 * of its own. On the ledger that is precisely how a posting looks correct in the API while its
 * audit trail is untraceable.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(TraceContractProfile::class)
class LedgerTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `posting a balanced journal keeps the request and its journal INSERT in one trace`() {
        // Deterministic posting accounts seeded by V3__ledger_governance.sql.
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
                  "description": "Trace contract posting",
                  "createdBy": "00000000-0000-0000-0000-000000000099",
                  "lines": [
                    {
                      "glAccountId": "$glAssetId",
                      "side": "DEBIT",
                      "amount": "1000.00",
                      "currencyCode": "CZK",
                      "baseAmount": "1000.00",
                      "baseCurrencyCode": "CZK"
                    },
                    {
                      "glAccountId": "$glLiabilityId",
                      "side": "CREDIT",
                      "amount": "1000.00",
                      "currencyCode": "CZK",
                      "baseAmount": "1000.00",
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
            body("id", notNullValue())
            body("status", equalTo("POSTED"))
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/journals")
            .requiresAttribute("POST /api/v1/journals", "http.response.status_code")
            .requiresAttribute("INSERT journal_entries", "db.sql.table")
            .requiresSameTrace("POST /api/v1/journals", "INSERT journal_entries")
            .hasNoErrorSpan()
            .verifiedAs("ledger-journal-post")
    }
}
