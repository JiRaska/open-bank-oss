// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the loyalty earn append.
 *
 * A posted earn is persisted to `leaf_ledger_entry` (with its outbox row in the same transaction)
 * before the HTTP caller is answered. The spans asserted here are the service's OWN — Quarkus'
 * server instrumentation produces the span for the real HTTP request, and Quarkus' JDBC
 * instrumentation produces the span for the real Postgres INSERT; [RecordingSpanExporter] only
 * adds an in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * ledger row happened *inside* the trace of the HTTP request that posted the earn. A persist that
 * escaped the request context — a fire-and-forget executor, a reactive hop that dropped the
 * context — still writes the row and still answers 201, is invisible to every persistence
 * assertion this module already had (LoyaltyLedgerOutboxIT asserts transactional pairing, not
 * causality), and lands in a trace of its own. For a ledger the row's traceability IS the audit
 * property: a leaf credit an operator cannot tie back to the request that awarded it is a
 * reconciliation gap, not a style issue.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the generic earn append is the service's primary write path, so it is the right first
 * contract here.
 */
@QuarkusTest
@TestProfile(LoyaltyTraceContractIT.LoyaltyTraceProfile::class)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR"])
class LoyaltyTraceContractIT {

    class LoyaltyTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(NoKafkaResource::class.java),
            QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java),
        )

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // The deployed referral stream has no broker in this contract's stack.
            "mp.messaging.incoming.referral-qualified-in.enabled" to "false",
        )
    }

    /** Disables the deployed referral consumer — same reason as the InMemory twin in other ITs. */
    class NoKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = emptyMap()

        override fun stop() = Unit
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `posting an earn keeps the request and its INSERT in one trace`() {
        val partyId = UUID.randomUUID()
        val correlation = UUID.randomUUID()

        Given {
            contentType("application/json")
            body("""{"earnSourceId":"SAVINGS_GOAL_REACHED","correlationEventId":"$correlation"}""")
        } When {
            post("/api/v1/loyalty/parties/$partyId/earn")
        } Then {
            statusCode(201)
        }

        awaitRow(partyId)

        exporter.contract()
            .requiresSpan("POST /api/v1/loyalty/parties/{partyId}/earn")
            .requiresSpan("INSERT leaf_ledger_entry")
            .requiresAttribute("INSERT leaf_ledger_entry", "db.sql.table")
            .requiresSameTrace("POST /api/v1/loyalty/parties/{partyId}/earn", "INSERT leaf_ledger_entry")
            .hasNoErrorSpan()
            .verifiedAs("loyalty-earn-append")
    }

    private fun awaitRow(partyId: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(partyId) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(partyId)).describedAs("leaf_ledger_entry row for $partyId").isEqualTo(1)
    }

    private fun count(partyId: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM leaf_ledger_entry WHERE party_id = ? AND earn_source_id = 'SAVINGS_GOAL_REACHED'",
        ).use { query ->
            query.setObject(1, partyId)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun config(name: String): String = ConfigProvider.getConfig().getValue(name, String::class.java)

    private fun jdbc() = DriverManager.getConnection(
        config("quarkus.datasource.jdbc.url"),
        config("quarkus.datasource.username"),
        config("quarkus.datasource.password"),
    )
}
