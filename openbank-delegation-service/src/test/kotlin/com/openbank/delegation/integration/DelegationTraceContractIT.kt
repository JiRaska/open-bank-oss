// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.infrastructure.client.ResilientScaChallengeClient
import com.openbank.delegation.it.PostgresTestResource
import com.openbank.delegation.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The observable distributed shape of the accept below.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres writes; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the UPDATE of the grant row and the
 * INSERT of its DelegationActivated event into `delegation_outbox` happened *inside* the
 * request's trace. [DelegationLifecycleRevisionIT] proves the aggregate and its outbox advance
 * together relationally; the trace half of that guarantee is what a support engineer searching
 * Tempo for "why did this grant reach the grantee's app" actually sees. A row/event pair that
 * escaped the request context still answers 200 and is invisible to every assertion this
 * module already had, but lands in traces of its own — the grant is active without an
 * observable cause.
 *
 * The grant is seeded through JDBC (no repository call, so no seed spans pollute the recorded
 * window and `requiresSameTrace` compares only the accept's own writes), and sca-service is
 * the single outbound seam the accept flow touches — replaced by a fake that behaves as
 * sca-service does, the same QuarkusMock pattern [com.openbank.delegation.integration] uses
 * elsewhere.
 */
@QuarkusTest
@QuarkusTestResource(DelegationTraceContractIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TraceContractProfile::class)
class DelegationTraceContractIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    private val sca = mockk<ResilientScaChallengeClient>()

    @BeforeEach
    fun installFakeSca() {
        QuarkusMock.installMockForType(sca, ResilientScaChallengeClient::class.java)
    }

    @Test
    @TestSecurity(user = "trace-contract", roles = ["ROLE_API"])
    fun `accepting a delegation keeps the request, its grant UPDATE and its outbox INSERT in one trace`() {
        val grantId = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        val scaSession = UUID.randomUUID()
        seedOffered(grantId, grantee)
        coEvery { sca.getChallenge(scaSession) } returns
            ScaChallengeSnapshot(scaSession, grantee, "DELEGATION_ACCEPT", "COMPLETED")
        coEvery { sca.consumeChallenge(scaSession, grantee) } returns
            ScaChallengeSnapshot(scaSession, grantee, "DELEGATION_ACCEPT", "CONSUMED")

        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", grantee.toString())
        } When {
            post(
                "/api/v1/delegations/$grantId/accept" +
                    "?granteePartyId=$grantee&scaSessionId=$scaSession",
            )
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/delegations/{id}/accept")
            .requiresAttribute("POST /api/v1/delegations/{id}/accept", "http.response.status_code")
            .requiresAttribute("UPDATE delegation_grants", "db.sql.table")
            .requiresAttribute("INSERT delegation_outbox", "db.sql.table")
            .requiresSameTrace(
                "POST /api/v1/delegations/{id}/accept",
                "UPDATE delegation_grants",
                "INSERT delegation_outbox",
            )
            .hasNoErrorSpan()
            .verifiedAs("delegation-accept-grant")
    }

    private fun jdbcUrl(): String =
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)

    private fun seedOffered(id: UUID, grantee: UUID) {
        val grantor = UUID.randomUUID()
        val resource = UUID.randomUUID()
        val validFrom = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        DriverManager.getConnection(jdbcUrl(), "openbank", "openbank_secret").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                  (id, grantor_party_id, grantee_party_id, resource_type, resource_id,
                   approval_policy, valid_from, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACCOUNT', ?, 'SOLO', ?, 'OFFERED', NOW(), NOW())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, grantor)
                statement.setObject(3, grantee)
                statement.setObject(4, resource)
                statement.setObject(5, validFrom)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "ACCOUNT_READ_BALANCES")
                statement.executeUpdate()
            }
        }
    }
}
