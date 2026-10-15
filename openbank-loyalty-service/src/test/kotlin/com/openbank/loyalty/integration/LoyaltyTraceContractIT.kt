// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.loyalty.it.LoyaltyPostgresTestResource
import com.openbank.loyalty.it.TraceContractProfile
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
 * The observable distributed shape of the earn below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the leaf-ledger lot and
 * the INSERT of its earned event into `loyalty_outbox` happened *inside* the request's trace.
 * [LoyaltyLedgerOutboxIT] proves the lot and its outbox row commit together relationally (a
 * plain JDBC read-back over the same transaction); the trace half of that guarantee is what a
 * support engineer searching Tempo for "why did this party's balance jump" actually sees. A
 * row/event pair that escaped the request context still answers 201 and is invisible to every
 * assertion this module already had, but lands in traces of its own — the leaves exist without
 * an observable cause.
 */
@QuarkusTest
@QuarkusTestResource(LoyaltyPostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class LoyaltyTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_OPERATOR"])
    fun `earning leaves keeps the request, its ledger INSERT and its outbox INSERT in one trace`() {
        val party = UUID.randomUUID()
        val correlation = UUID.randomUUID()

        Given {
            contentType("application/json")
            body("""{"earnSourceId":"SAVINGS_GOAL_REACHED","correlationEventId":"$correlation"}""")
        } When {
            post("/api/v1/loyalty/parties/$party/earn")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/loyalty/parties/{partyId}/earn")
            .requiresAttribute("POST /api/v1/loyalty/parties/{partyId}/earn", "http.response.status_code")
            .requiresAttribute("INSERT leaf_ledger_entry", "db.sql.table")
            .requiresAttribute("INSERT loyalty_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/loyalty/parties/{partyId}/earn",
                "INSERT leaf_ledger_entry",
                "INSERT loyalty_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("loyalty-earn-award")
    }
}
