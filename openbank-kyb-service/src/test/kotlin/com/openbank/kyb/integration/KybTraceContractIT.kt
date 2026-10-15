// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.it.PostgresTestResource
import com.openbank.kyb.it.TraceContractProfile
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
 * The observable distributed shape of the case start below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT of the case row and the
 * INSERT of its KybCaseStarted event into `kyb_outbox` happened *inside* the request's trace.
 * [KybCaseApiIT] proves the case row and its outbox event commit together relationally (a
 * plain JDBC read-back); the trace half of that guarantee is what a compliance engineer
 * searching Tempo for "where did this onboarding case come from" actually sees. A row/event
 * pair that escaped the request context still answers 201 and is invisible to every
 * assertion this module already had, but lands in traces of its own — the case exists
 * without an observable cause.
 *
 * The identifier used is a valid CZ_ICO the test registry has no record for, so the flow
 * takes the designed MANUAL_REVIEW branch: one real request is the whole recorded window,
 * with no seeding and no outbound stubs — the registry is the module's canned fake, and the
 * party/timer gateways are its normal test seams.
 */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class KybTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_API"])
    fun `starting a kyb case keeps the request, its case INSERT and its outbox INSERT in one trace`() {
        val initiator = UUID.randomUUID()

        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", initiator.toString())
            body("""{"scheme":"CZ_ICO","identifier":"10000003","initiatorPartyId":"$initiator"}""")
        } When {
            post("/api/v1/kyb/cases")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/kyb/cases")
            .requiresAttribute("POST /api/v1/kyb/cases", "http.response.status_code")
            .requiresAttribute("INSERT kyb_cases", "db.sql.table")
            .requiresAttribute("INSERT kyb_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/kyb/cases",
                "INSERT kyb_cases",
                "INSERT kyb_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("kyb-start-case")
    }
}
