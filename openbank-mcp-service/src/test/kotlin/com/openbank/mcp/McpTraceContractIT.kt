// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.mcp

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
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
 * The observable distributed shape of the staff OBO session issue (ADR-0224).
 *
 * A posted session is persisted to `agent_session` before the HTTP caller is answered CREATED.
 * The spans asserted here are the service's OWN — Quarkus' server instrumentation produces the
 * span for the real HTTP request, and Quarkus' JDBC instrumentation produces the span for the
 * real Postgres INSERT; [RecordingSpanExporter] only adds an in-memory `SpanProcessor` so a
 * contract can read what an OTLP collector would receive. Nothing asserted here is created by
 * the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * session row happened *inside* the trace of the HTTP request that issued it. A persist that
 * escaped the request context — a fire-and-forget executor, a reactive hop that dropped the
 * context — still writes the row and still answers 201, is invisible to every persistence
 * assertion this module already had (McpSessionLifecycleIT asserts lifecycle state, not
 * causality), and lands in a trace of its own. For an OBO session the row's traceability backs
 * the security property: an operator must be able to tie a live agent credential back to the
 * authenticated human request that minted it (#3182 is the class of bug where identity drift
 * made that impossible).
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the session issue is the service's primary cross-process write, so it is the right
 * first contract here.
 */
@QuarkusTest
@TestProfile(McpTraceContractIT.McpTraceProfile::class)
@TestSecurity(user = "trace.operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
class McpTraceContractIT {

    class McpTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(PostgresTestResource::class.java),
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `issuing an OBO session keeps the request and its INSERT in one trace`() {
        val marker = UUID.randomUUID()

        Given {
            contentType("application/json")
            body("""{"clientId":"trace-$marker","roleCeiling":["ROLE_OPERATOR"],"purpose":"trace-contract"}""")
        } When {
            post("/api/v1/mcp/sessions")
        } Then {
            statusCode(201)
        }

        awaitRow(marker)

        exporter.contract()
            .requiresSpan("POST /api/v1/mcp/sessions")
            .requiresSpan("INSERT agent_session")
            .requiresAttribute("INSERT agent_session", "db.sql.table")
            .requiresSameTrace("POST /api/v1/mcp/sessions", "INSERT agent_session")
            .hasNoErrorSpan()
            .verifiedAs("mcp-session-issue")
    }

    private fun awaitRow(marker: UUID) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(marker) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(marker)).describedAs("agent_session row for $marker").isEqualTo(1)
    }

    private fun count(marker: UUID): Int = jdbc().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM agent_session WHERE client_id = ?").use { query ->
            query.setString(1, "trace-$marker")
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
