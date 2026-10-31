// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.wealth.it.PostgresTestResource
import com.openbank.wealth.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of the declaration below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the holding row and the
 * INSERT of its event into `wealth_outbox` happened *inside* the request's trace.
 * [WealthHoldingApiIT] proves the pair commits together relationally (a plain JDBC read-back);
 * the trace half of that guarantee is what a support engineer searching Tempo for "why did
 * this collateral reach the lending valuation" actually sees. A row/event pair that escaped
 * the request context still answers 201 and is invisible to every assertion this module
 * already had, but lands in traces of its own — the holding exists without an observable
 * cause.
 */
@QuarkusTest
@QuarkusTestResource(WealthTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class WealthTraceContractIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("wealth-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_API"])
    fun `declaring a holding keeps the request, its row INSERT and its outbox INSERT in one trace`() {
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
            body(
                """{
                  "holdingType": "COLLECTIBLE",
                  "label": "trace contract",
                  "valuation": {
                    "amount": 250000.00,
                    "currency": "CZK",
                    "valuedAt": "2026-09-01",
                    "source": "CUSTOMER_DECLARED"
                  },
                  "ownershipShare": 1,
                  "documentIds": []
                }""",
            )
        } When {
            post("/api/v1/holdings")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/holdings")
            .requiresAttribute("POST /api/v1/holdings", "http.response.status_code")
            .requiresAttribute("INSERT declared_holdings", "db.sql.table")
            .requiresAttribute("INSERT wealth_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/holdings",
                "INSERT declared_holdings",
                "INSERT wealth_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("wealth-declare-holding")
    }
}
