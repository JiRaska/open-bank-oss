// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.it.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of the contribution-schedule read below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres read; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the SELECT that actually looked up the
 * contract happened *inside* the request's trace. A read that escaped the request context —
 * an un-propagated reactive hop, a coroutine dispatched off the request span — still reaches the
 * database and still answers, is invisible to every presence assertion this module already had,
 * and lands in a trace of its own.
 *
 * The contribution schedule is the right subject for pension: it is the in-force record of what
 * a participant pays, read by operators, the participant app and payout processing alike — so a
 * schedule read that lost its trace context corrupts the observability of exactly the money
 * figure every downstream consumer relies on. The contract uses a non-existent contract id: the
 * endpoint still performs the lookup SELECT before answering 404, and the trace claim is about
 * the lookup's context, not about the response status.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class PensionTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `reading a contribution schedule keeps the request and its pension_contracts SELECT in one trace`() {
        val contractId = UUID.randomUUID()

        When {
            get("/api/v2/pension/contracts/$contractId/contribution-schedule")
        } Then {
            statusCode(404)
        }

        exporter.contract()
            .requiresSpan("GET /api/v2/pension/contracts/{contractId}/contribution-schedule")
            .requiresAttribute(
                "GET /api/v2/pension/contracts/{contractId}/contribution-schedule",
                "http.response.status_code",
            )
            .requiresAttribute("SELECT pension_contracts", "db.sql.table")
            .requiresSameTrace(
                "GET /api/v2/pension/contracts/{contractId}/contribution-schedule",
                "SELECT pension_contracts",
            )
            .hasNoErrorSpan()
            .verifiedAs("pension-contribution-schedule-read")
    }
}
