// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import com.openbank.settlement.it.PostgresTestResource
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
import java.util.UUID

/**
 * The observable distributed shape of interbank settlement origination.
 *
 * `POST /api/v1/settlements` persists the `settlements` row AND its
 * `SETTLEMENT_STATE_CHANGED` `settlement_outbox` row in ONE transaction (`SettlementRepositoryImpl.create`
 * → `SettlementAuditWriter.append`), then starts the durable settlement workflow. The outbox row is
 * the auditable state fact every downstream consumer reconciles against, so the two INSERTs ARE
 * the money write. The spans asserted here are the service's OWN: Quarkus' OpenTelemetry
 * auto-instrumentation produces them for the real HTTP request and the real Postgres writes;
 * `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the settlement row and its outbox
 * fact happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still
 * answers 201, is invisible to every presence assertion this module already had, and lands in a
 * trace of its own. For settlement that is worse than for a read model: an orphaned outbox fact
 * is a state change downstream consumers can act on that no operator can reconstruct from
 * telemetry, on the fleet's interbank money path.
 *
 * The workflow start goes to the module-wide in-process `TestWorkflowEnvironment`
 * (`WorkflowClientTestProducer`), so no real Temporal frontend is needed; the worker stays off in
 * tests and the no-op workflow registers its own.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); settlement origination is this service's most
 * important write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(SettlementTraceContractIT.SettlementTraceProfile::class)
class SettlementTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder, dispatcher off). The one
     * billing-shaped addition is port 0: a profile's own boot re-binds the fixed default test
     * port 8081 before the outgoing instance has released it (`QuarkusBindException` — measured
     * in billing's `BillingOutboxAtomicityIT`); port 0 lets Quarkus pick a free one and rewrites
     * the property so RestAssured still finds it.
     */
    class SettlementTraceProfile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.http.test-port" to "0",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `originating a settlement keeps the request and both money rows in one trace`() {
        val payer = UUID.randomUUID()
        val payee = UUID.randomUUID()

        Given {
            contentType("application/json")
            body(
                """
                {
                  "idempotencyKey": "trace-${UUID.randomUUID()}",
                  "payerAccountId": "$payer",
                  "payeeAccountId": "$payee",
                  "amount": "125.50",
                  "currency": "CZK"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/settlements")
        } Then {
            statusCode(201)
            body("status", equalTo("PENDING"))
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/settlements")
            .requiresAttribute("POST /api/v1/settlements", "http.response.status_code")
            .requiresAttribute("INSERT settlements", "db.sql.table")
            .requiresAttribute("INSERT settlement_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/settlements",
                "INSERT settlements",
                "INSERT settlement_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("settlement-originate")
    }
}
