// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.openbank.delegation.it.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
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
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * The observable distributed shape of a spend reservation.
 *
 * `POST /api/v1/delegations/{id}/reservations` commits the
 * `delegation_spend_reservations` row and its `SpendReserved` `delegation_outbox` row in ONE
 * transaction (ADR-0249 D4, proved row-wise by [SpendReservationOutboxWriteIT]). The reservation
 * holds part of a delegation grant's daily limit, and the outbox row is the audit trail
 * `AuditConsumer` reconciles against — with the `sourceService` attribution it silently falls
 * back to "unknown" for when a producer omits it (#3994/#5256) — so the two INSERTs ARE the
 * delegation money write. The spans asserted here are the service's OWN: Quarkus' OpenTelemetry
 * auto-instrumentation produces them for the real HTTP request and the real Postgres writes;
 * `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing assertion: it says the reservation row and its audit
 * event happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the rows and still
 * answers 201, is invisible to every presence assertion this module already had, and lands in a
 * trace of its own: a reservation against the grant's limit that no operator can reconstruct
 * from telemetry.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); the spend reservation is this service's only write
 * that directly holds customer funds, so it is the right first contract here.
 */
@QuarkusTest
@QuarkusTestResource(DelegationTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DelegationTraceContractIT.DelegationTraceProfile::class)
class DelegationTraceContractIT {

    /**
     * Both outgoing channels go in-memory for the same reason as in [SpendReservationOutboxWriteIT]:
     * the test JVM has no broker, and the profile's own boot would otherwise ask SmallRye for one.
     */
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "approval-group-revisions-out",
        ) + mapOf("quarkus.kafka.devservices.enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    /** The libs baseline alone suffices: this module's `%test` already pins `test-port: 0`. */
    class DelegationTraceProfile : TraceContractProfile()

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var exporter: RecordingSpanExporter

    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()

    /** Seeds an ACTIVE grant with the payment capability — JDBC, no ORM in the arrangement. */
    private fun seedGrant(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                insert into delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                     daily_limit_amount, daily_limit_currency, valid_from, valid_to, status, created_at, updated_at)
                values (?, ?, ?, 'ACCOUNT', ?, 'SOLO', 5000.00, 'CZK',
                        now() - interval '1 day', now() + interval '30 days', 'ACTIVE', now(), now())
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, grantor)
                ps.setObject(3, grantee)
                ps.setObject(4, UUID.randomUUID())
                ps.executeUpdate()
            }
            c.prepareStatement(
                "insert into delegation_capabilities (grant_id, capability) values (?, 'ACCOUNT_INITIATE_PAYMENT')",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
        return id
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_API"])
    fun `reserving spend keeps the request and both money rows in one trace`() {
        val grantId = seedGrant()

        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", grantee.toString())
            body("""{"amount": 1250.00, "currency": "CZK", "idempotencyKey": "trace-it-${System.nanoTime()}"}""")
        } When {
            post("/api/v1/delegations/$grantId/reservations")
        } Then {
            statusCode(201)
            body("reservationId", notNullValue())
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/delegations/{id}/reservations")
            .requiresAttribute("POST /api/v1/delegations/{id}/reservations", "http.response.status_code")
            .requiresAttribute("INSERT delegation_spend_reservations", "db.sql.table")
            .requiresAttribute("INSERT delegation_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/delegations/{id}/reservations",
                "INSERT delegation_spend_reservations",
                "INSERT delegation_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("delegation-spend-reserve")
    }
}
