// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.integration

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
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The observable distributed shape of the unified-identity party create (ADR-0072).
 *
 * A posted party is persisted to `parties` before the HTTP caller is answered. The spans asserted
 * here are the service's OWN — Quarkus' server instrumentation produces the span for the real
 * HTTP request, and Quarkus' JDBC instrumentation produces the span for the real Postgres INSERT;
 * [RecordingSpanExporter] only adds an in-memory `SpanProcessor` so a contract can read what an
 * OTLP collector would receive. Nothing asserted here is created by the test, so every assertion
 * can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * party row happened *inside* the trace of the HTTP request that created it. A persist that
 * escaped the request context — a fire-and-forget executor, a reactive hop that dropped the
 * context — still writes the row and still answers 200/201, is invisible to every persistence
 * assertion this module already had (PidApiContractTest asserts wire shape, not causality), and
 * lands in a trace of its own. For the identity index the row's traceability backs the
 * evidentiary chain: every downstream KYC/AML decision names this party id, so an operator must
 * be able to tie the row back to the authenticated request that minted it.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the party create is the service's primary write, so it is the right first contract here.
 */
@QuarkusTest
@TestProfile(PidTraceContractIT.PidTraceProfile::class)
@TestSecurity(user = "trace-operator", roles = ["ROLE_OPERATOR"])
class PidTraceContractIT {

    class PidTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(InMemoryKafkaResource::class.java),
            QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java),
        )
    }

    /** Same InMemory-channel twin as PidApiContractTest — no broker in this contract's stack. */
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("party-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `creating a party keeps the request and its INSERT in one trace`() {
        val marker = UUID.randomUUID()

        Given {
            contentType("application/json")
            body(
                """{"givenName":"Trace","familyName":"$marker","birthdate":"1990-01-01","bankIdSub":"trace-$marker"}""",
            )
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        }

        awaitRow(marker)

        exporter.contract()
            .requiresSpan("POST /api/v1/parties")
            .requiresSpan("INSERT parties")
            .requiresAttribute("INSERT parties", "db.sql.table")
            .requiresSameTrace("POST /api/v1/parties", "INSERT parties")
            .hasNoErrorSpan()
            .verifiedAs("pid-party-create")
    }

    private fun awaitRow(marker: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(marker) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(marker)).describedAs("parties row for $marker").isEqualTo(1)
    }

    private fun count(marker: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM parties WHERE family_name = ?",
        ).use { query ->
            query.setString(1, marker.toString())
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
