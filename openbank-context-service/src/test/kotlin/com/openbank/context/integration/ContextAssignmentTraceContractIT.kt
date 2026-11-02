// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The observable distributed shape of an assignment proposal write.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real reactive Postgres writes; the recorder only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the two INSERTs that persisted the proposal
 * and its maker-checker audit row happened *inside* the request's trace. A write that escaped the
 * request context — an un-propagated reactive hop, a fire-and-forget executor — still writes the
 * row and still answers 201, is invisible to every assertion this module already had, and lands in
 * a trace of its own. That is exactly what a maker-checker audit trail must not do silently.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_trace")],
)
@TestProfile(ContextTraceContractProfile::class)
class ContextAssignmentTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract-admin", roles = ["ROLE_ADMIN"])
    fun `proposing an assignment keeps the request and both INSERTs in one trace`() {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "principalId": "trace-contract-investigator",
                  "caseId": "case-trace-${UUID.randomUUID()}",
                  "purpose": "PAYMENT_COMPLAINT",
                  "validTo": "${Instant.now().plusSeconds(3600)}",
                  "rootRef": "complaint:CMP-TRACE-1"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/context/assignment-proposals")
        } Then {
            statusCode(201)
            body("status", equalTo("PENDING"))
            body("makerId", equalTo("trace-contract-admin"))
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/context/assignment-proposals")
            .requiresAttribute("POST /api/v1/context/assignment-proposals", "http.response.status_code")
            .requiresAttribute("INSERT context_assignment_proposals", "db.sql.table")
            .requiresSpan("INSERT context_assignment_change_audit")
            .requiresSameTrace(
                "POST /api/v1/context/assignment-proposals",
                "INSERT context_assignment_proposals",
                "INSERT context_assignment_change_audit",
            )
            .hasNoErrorSpan()
            .verifiedAs("context-assignment-propose")
    }
}
