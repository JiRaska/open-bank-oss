// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The observable distributed shape of drafting a suspicious-transaction report.
 *
 * `POST /api/v1/aml/reports` persists the STR draft (`aml_str`, with its case and movement link
 * rows) that the approve → file lifecycle hangs off (ADR-0371 §6, §7). The spans asserted here
 * are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them for the real
 * HTTP request and the real Postgres write; `TraceContractRecorder` only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * report happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 201, is invisible to every assertion this module already had, and lands in a trace of
 * its own. For an STR the row IS the report the FIU eventually receives; a draft no investigator
 * can tie back to the request that created it is a gap in the AML evidence trail.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); report drafting is the service's most important write, so it is the right first contract
 * here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(AmlTraceContractIT.AmlTraceProfile::class)
class AmlTraceContractIT {

    /** The libs baseline gives this class its own boot (own recorder, dispatcher off). */
    class AmlTraceProfile : TraceContractProfile() {

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // Pin the sampler so the contract is hermetic: a parent-based sampler makes it a
            // function of an incoming traceparent's sampled flag the test does not control, and
            // an unsampled parent legitimately yields no spans (observed as 'observed span names
            // only: []'). Sampling policy is the deployment's concern, not the contract's.
            "quarkus.otel.traces.sampler" to "always_on",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_COMPLIANCE"])
    fun `drafting a report keeps the request and its report INSERT in one trace`() {
        // The draft validates that every linked case is a TRANSACTION_MONITORING case of the
        // party and every linked movement is that party's booked movement, so the fixture seeds
        // exactly one of each over JDBC before the request.
        val partyId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val journalEntryId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        seedMonitoringCase(partyId, caseId)
        seedBookedMovement(partyId, journalEntryId, accountId)

        Given {
            contentType("application/json")
            body(
                """
                {"partyId":"$partyId","narrative":"Trace contract probe.",
                 "caseIds":["$caseId"],
                 "movements":[{"journalEntryId":"$journalEntryId",
                               "accountId":"$accountId","currency":"CZK"}]}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/aml/reports")
        } Then {
            statusCode(201)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/aml/reports")
            .requiresAttribute("POST /api/v1/aml/reports", "http.response.status_code")
            .requiresAttribute("INSERT aml_str", "db.sql.table")
            .requiresSameTrace("POST /api/v1/aml/reports", "INSERT aml_str")
            .hasNoErrorSpan()
            .verifiedAs("aml-str-draft")
    }

    private fun seedMonitoringCase(partyId: UUID, caseId: UUID) = jdbc().use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO aml_cases
              (case_id, idempotency_key, party_id, customer_reference, screening_type, risk_level,
               status, alert_code, screened_at, created_at)
            VALUES (?, ?, ?, 'trace-contract', 'TRANSACTION_MONITORING', 'HIGH',
                    'OPEN', 'TRACE_PROBE', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, caseId)
            statement.setString(2, "tm:trace-$caseId")
            statement.setObject(3, partyId)
            statement.setTimestamp(4, Timestamp.from(Instant.now()))
            statement.setTimestamp(5, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun seedBookedMovement(partyId: UUID, journalEntryId: UUID, accountId: UUID) = jdbc().use { connection ->
        connection.prepareStatement(
            """
                INSERT INTO aml_booked_movement
                  (journal_entry_id, account_id, currency, direction, amount, transaction_id,
                   entry_date, booked_at, recorded_at, party_id, rail, counted_at)
                VALUES (?, ?, 'CZK', 'IN', 1, ?, CURRENT_DATE, ?, ?, ?, 'DOMESTIC', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, journalEntryId)
            statement.setObject(2, accountId)
            statement.setObject(3, UUID.randomUUID())
            statement.setTimestamp(4, Timestamp.from(Instant.now()))
            statement.setTimestamp(5, Timestamp.from(Instant.now()))
            statement.setObject(6, partyId)
            statement.setTimestamp(7, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun jdbc() = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)
}
