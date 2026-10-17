// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.statement.it.PostgresTestResource
import com.openbank.statement.it.TraceContractProfile
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
 * The observable distributed shape of the statement list below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres read; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the SELECT that actually answered the
 * listing happened *inside* the request's trace. A read that escaped the request context — an
 * un-propagated reactive hop, a client call on a separate executor — still returns rows and
 * still answers 200, is invisible to every presence assertion this module already had, and lands
 * in a trace of its own.
 *
 * The listing is the right subject for statement: every downstream consumer (customer 360,
 * document render, ad-hoc export) treats `statement_period` as the legal record of closed
 * periods, so a read that lost its trace context corrupts every dependent observation, not just
 * one response.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class StatementTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_VIEWER", "ROLE_OPERATOR"])
    fun `listing statements keeps the request and its statement_period SELECT in one trace`() {
        val accountId = UUID.randomUUID()

        When {
            get("/api/v1/statements/$accountId")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("GET /api/v1/statements/{accountId}")
            .requiresAttribute("GET /api/v1/statements/{accountId}", "http.response.status_code")
            .requiresAttribute("SELECT statement_period", "db.sql.table")
            .requiresSameTrace("GET /api/v1/statements/{accountId}", "SELECT statement_period")
            .hasNoErrorSpan()
            .verifiedAs("statement-list-account")
    }
}
