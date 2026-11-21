// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.copilot.integration

import com.openbank.copilot.it.CopilotPostgresTestResource
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
 * The observable distributed shape of the customer chat turn (ADR-0089).
 *
 * A posted chat turn runs the governed reasoning loop and persists the turn to
 * `conversation_history` (the conversation memory ADR-0031 D5 retention governs) before the HTTP
 * caller is answered. The spans asserted here are the service's OWN — Quarkus' server
 * instrumentation produces the span for the real HTTP request, and Quarkus' JDBC instrumentation
 * produces the span for the real Postgres INSERT; [RecordingSpanExporter] only adds an in-memory
 * `SpanProcessor` so a contract can read what an OTLP collector would receive. Nothing asserted
 * here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually committed the
 * turn happened *inside* the trace of the HTTP request that posted it. A persist that escaped the
 * request context — the `runBlocking` bridge dropping context mid-ceremony, a reactive hop
 * crossing threads — still writes the row and still answers 200, is invisible to every functional
 * assertion this module already had, and lands in a trace of its own. For conversation memory
 * that traceability backs the retention/evidence property: a turn an operator cannot tie back to
 * the request that produced it cannot be audited or erased with confidence.
 *
 * The model seam is the config-driven `mock` provider (ADR-0089 D6) — the same sandbox backend
 * the default profile ships, no external LLM. The guardrails run for real: the prompt-injection
 * scan and the content-safety port (disabled by default, visibly UNAVAILABLE — never a silent
 * SAFE) are both on the exercised path.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the chat turn is the service's primary write, so it is the right first contract here.
 */
@QuarkusTest
@TestProfile(CopilotTraceContractIT.CopilotTraceProfile::class)
@TestSecurity(user = "trace-customer", roles = ["ROLE_CUSTOMER"])
class CopilotTraceContractIT {

    class CopilotTraceProfile : TraceContractProfile() {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(CopilotPostgresTestResource::class.java),
        )

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // The flag the whole contract exists to exercise — off by default (ADR-0067).
            "copilot.enabled" to "true",
            // @Authorize on CopilotChatResource — the deployed OPA sidecar is not in this stack.
            "authz.enforce" to "false",
            // The deployed party-erasure stream has no broker in this contract's stack.
            "mp.messaging.incoming.party-events-in.enabled" to "false",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `posting a chat turn keeps the request and its INSERT in one trace`() {
        val conversationId = "trace-${UUID.randomUUID()}"

        Given {
            contentType("application/json")
            body("""{"conversationId":"$conversationId","message":"Ahoj, co umíš?"}""")
        } When {
            post("/api/v1/copilot/chat")
        } Then {
            statusCode(200)
        }

        awaitRow("trace-customer", conversationId)

        exporter.contract()
            .requiresSpan("POST /api/v1/copilot/chat")
            .requiresSpan("INSERT conversation_history")
            .requiresAttribute("INSERT conversation_history", "db.sql.table")
            .requiresSameTrace("POST /api/v1/copilot/chat", "INSERT conversation_history")
            .hasNoErrorSpan()
            .verifiedAs("copilot-chat-turn-append")
    }

    private fun awaitRow(customerId: String, conversationId: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count(customerId, conversationId) == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(count(customerId, conversationId))
            .describedAs("conversation_history row for $customerId/$conversationId").isEqualTo(1)
    }

    private fun count(customerId: String, conversationId: String): Int = jdbc().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM conversation_history WHERE customer_id = ? AND conversation_id = ?",
        ).use { query ->
            query.setString(1, customerId)
            query.setString(2, conversationId)
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
