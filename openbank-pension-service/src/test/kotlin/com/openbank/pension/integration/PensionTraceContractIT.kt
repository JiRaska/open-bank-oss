// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * The observable distributed shape of a matched pension contribution intake.
 *
 * `POST /api/v2/pension/funding/operations/payments` credits a contract by INSERTing the
 * `pension_contributions` row (plus the reference/lookup reads) in the request's transaction — the
 * row the participant's tax-year certificates, state-incentive claims and clawbacks all reconcile
 * against, so it IS the pension money write. The spans asserted here are the service's OWN:
 * Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP request and the real
 * Postgres writes; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can
 * read what an OTLP collector would receive. Nothing asserted here is created by the test, so
 * every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the money INSERT happened *inside* the
 * request's trace. A write that escaped the request context — an un-propagated reactive hop, a
 * fire-and-forget executor — still writes the row and still answers 200, is invisible to every
 * presence assertion this module already had (FundingApiIT proves the rows land; nothing there
 * proves they landed in the caller's trace), and lands in a trace of its own: a participant
 * contribution no operator can reconstruct from telemetry.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); contribution intake is this service's highest-volume
 * money write, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PensionTraceContractIT.PensionTraceProfile::class)
class PensionTraceContractIT {

    /** The plain libs baseline suffices: this module's `%test` already pins `test-port: 0`. */
    class PensionTraceProfile : TraceContractProfile()

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var exporter: RecordingSpanExporter

    /**
     * An ACTIVE contract written straight to the table (the FundingApiIT arrangement): staff may
     * not create a contract over HTTP, so the seed is JDBC. Ids sit far above Hibernate's
     * pooled-sequence range — the BIGSERIAL default and the entity's sequence blocks collide.
     */
    private fun seededContract(party: UUID): String {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction, " +
                    "pack_version, " +
                    "provider_entity_id, provider_type, participant_birth_date, status, contribution_amount, " +
                    "contribution_currency, contribution_frequency, start_date, created_at, updated_at) " +
                    "VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 2, ?, " +
                    "'PENSION_COMPANY', DATE '1985-05-05', 'ACTIVE', 1700, 'CZK', " +
                    "'MONTHLY', DATE '2025-01-01', now(), now())",
            ).use { st ->
                st.setObject(1, id)
                st.setObject(2, party)
                st.setObject(3, UUID.randomUUID())
                st.executeUpdate()
            }
            // The aggregate requires a strategy; the service rehydrates the contract through it.
            c.prepareStatement(
                "INSERT INTO pension_strategy_elections (id, contract_id, strategy_code, effective_from, elected_at) " +
                    "VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, 'BALANCED', DATE '2025-01-01', now())",
            ).use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
        }
        return id.toString()
    }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `crediting a matched contribution keeps the request and the money INSERT in one trace`() {
        val party = UUID.randomUUID()
        val contractId = seededContract(party)

        val reference: String = When {
            get("/api/v2/pension/funding/contracts/$contractId/payment-reference")
        } Then {
            statusCode(200)
        } Extract {
            path("reference")
        }

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """{"paymentId":"trace-it-${UUID.randomUUID()}","amount":1700,"currency":"CZK",""" +
                    """"valueDate":"2026-01-15","reference":"$reference"}""",
            )
        } When {
            post("/api/v2/pension/funding/operations/payments")
        } Then {
            statusCode(200)
            body("outcome", equalTo("CREDITED"))
            body("contribution.id", notNullValue())
        }

        exporter.contract()
            .requiresSpan("POST /api/v2/pension/funding/operations/payments")
            .requiresAttribute("POST /api/v2/pension/funding/operations/payments", "http.response.status_code")
            .requiresAttribute("INSERT pension_contributions", "db.statement")
            .requiresSameTrace(
                "POST /api/v2/pension/funding/operations/payments",
                "INSERT pension_contributions",
            )
            .hasNoErrorSpan()
            .verifiedAs("pension-contribution-intake")
    }
}
