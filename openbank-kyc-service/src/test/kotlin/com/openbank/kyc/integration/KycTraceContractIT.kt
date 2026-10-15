// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.openbank.kyc.it.TraceContractProfile
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
import javax.sql.DataSource

/**
 * The observable distributed shape of the approval below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the UPDATE that flipped `kyc_cases` to
 * APPROVED and the INSERT that recorded KYC_CASE_APPROVED in `kyc_outbox` happened *inside*
 * the request's trace. Issue #4007 exists because that pair could diverge — state committed
 * without its event, or an event emitted for a state that rolled back. A write pair that
 * escaped the request context still answers 200 and is invisible to every assertion this
 * module already had; landing the pair in one trace is the distributed half of the same
 * guarantee [KycOutboxWriteIT] proves relationally.
 *
 * The case is seeded to UNDER_REVIEW via JDBC (mirroring [KycOutboxWriteIT]'s read-back
 * style) so the recorded window contains exactly the one request under contract — the
 * approval — and `requiresSameTrace` compares its spans against each other, not against the
 * same-named spans of a setup flow. `approveCase` requires only status = UNDER_REVIEW; the
 * four PASSED checks gate the transition INTO review, which seeding bypasses deliberately.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.kyc.it.PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class KycTraceContractIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_ADMIN", "ROLE_KYC_REVIEWER"])
    fun `approving a case keeps the request, its status UPDATE and its outbox INSERT in one trace`() {
        val caseId = UUID.randomUUID()
        seedUnderReviewCase(caseId)

        Given {
            contentType("application/json")
            body("""{"reason":"All documents verified by the trace contract"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/approve")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/kyc/cases/{caseId}/approve")
            .requiresAttribute("POST /api/v1/kyc/cases/{caseId}/approve", "http.response.status_code")
            .requiresAttribute("UPDATE kyc_cases", "db.sql.table")
            .requiresAttribute("INSERT kyc_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/kyc/cases/{caseId}/approve",
                "UPDATE kyc_cases",
                "INSERT kyc_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("kyc-approve-case")
    }

    private fun seedUnderReviewCase(caseId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into kyc_cases (case_id, party_id, status, risk_level)
                    values (?, ?, 'UNDER_REVIEW', 'MEDIUM')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, caseId)
                statement.setObject(2, UUID.randomUUID())
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }
}
