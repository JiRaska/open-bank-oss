// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.integration

import com.openbank.dispute.it.TraceContractProfile
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

/**
 * The observable distributed shape of the filing below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the complaint row and the
 * INSERT of its COMPLAINT_FILED event into `dispute_outbox` happened *inside* the request's
 * trace. [ComplaintRevisionIT] proves the aggregate and its outbox advance together
 * relationally; the trace half of that guarantee is what a support engineer searching Tempo
 * for "why did this complaint reach the scheme adapter" actually sees. A row/event pair that
 * escaped the request context still answers 201 and is invisible to every assertion this
 * module already had, but lands in traces of its own — the complaint exists without an
 * observable cause.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.dispute.it.PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class DisputeTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_OPERATOR"])
    fun `filing a complaint keeps the request, its row INSERT and its outbox INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """{"category":"PAYMENT_SERVICE","channel":"APP","description":"duplicate debit", """ +
                    """"accountId":null,"transactionId":null,"disputeId":null}""",
            )
        } When {
            post("/api/v1/complaints")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/complaints")
            .requiresAttribute("POST /api/v1/complaints", "http.response.status_code")
            .requiresAttribute("INSERT complaints", "db.sql.table")
            .requiresAttribute("INSERT dispute_outbox", "db.sql.table")
            .requiresSameTrace("POST /api/v1/complaints", "INSERT complaints", "INSERT dispute_outbox")
            .hasNoErrorSpan()
            .verifiedAs("dispute-file-complaint")
    }
}
