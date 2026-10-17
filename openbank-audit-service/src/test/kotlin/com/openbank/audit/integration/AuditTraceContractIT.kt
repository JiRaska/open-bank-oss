// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.it.PostgresTestResource
import com.openbank.audit.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
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
 * The observable distributed shape of the audit-trail read below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for
 * the real HTTP request and the real Postgres read; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the SELECT that actually answered the
 * trail query happened *inside* the request's trace. A read that escaped the request context —
 * an un-propagated reactive hop, a client call on a separate executor — still returns entries
 * and still answers 200, is invisible to every presence assertion this module already had, and
 * lands in a trace of its own.
 *
 * The trail read is the right subject for audit: the hash-chained `audit_entries` store is
 * regulated evidence (SOX/DORA), and every investigation (auditor, compliance, credit-risk
 * evidence bundle) depends on observing it in the caller's own trace context — a trail read that
 * lost its trace context breaks the evidentiary chain of the very request asking for evidence.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class AuditTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "auditor", roles = ["ROLE_AUDITOR"])
    fun `reading an audit trail keeps the request and its audit_entries SELECT in one trace`() {
        val aggregateId = UUID.randomUUID().toString()

        When {
            get("/api/v1/audit/entries/$aggregateId")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("GET /api/v1/audit/entries/{aggregateId}")
            .requiresAttribute("GET /api/v1/audit/entries/{aggregateId}", "http.response.status_code")
            .requiresAttribute("SELECT audit_entries", "db.sql.table")
            .requiresSameTrace("GET /api/v1/audit/entries/{aggregateId}", "SELECT audit_entries")
            .hasNoErrorSpan()
            .verifiedAs("audit-trail-read")
    }
}
