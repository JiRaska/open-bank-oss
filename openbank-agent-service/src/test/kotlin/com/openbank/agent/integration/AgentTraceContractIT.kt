// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The observable distributed shape of halting an agent scope.
 *
 * `POST /api/v1/admin/agents/halt` persists the kill-switch row (`agent_kill_switch`) that every
 * gated agent path checks before it acts — the fleet's answer to a runaway agent is only as good
 * as the trace that proves the halt landed. The spans asserted here are the service's OWN —
 * Quarkus' OpenTelemetry auto-instrumentation produces them for the real HTTP request and the
 * real Postgres write; `TraceContractRecorder` only adds an in-memory `SpanProcessor` so a
 * contract can read what an OTLP collector would receive. Nothing asserted here is created by
 * the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the INSERT that actually persisted the
 * halt happened *inside* the request's trace. A write that escaped the request context — an
 * un-propagated reactive hop, a fire-and-forget executor — still writes the row and still
 * answers 200, is invisible to every assertion this module already had, and lands in a trace of
 * its own. For a kill switch that is the worst possible gap: the operator sees 200, the halt is
 * in the table, and no telemetry anywhere connects it to the request that set it.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a
 * gap); the kill-switch halt is the service's highest-stakes write, so it is the right first
 * contract here.
 */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    restrictToAnnotatedClass = true,
    initArgs = [ResourceArg(name = "db", value = "openbank_agent_trace_it")],
)
@QuarkusTestResource(AgentTraceContractIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
@TestProfile(AgentTraceContractIT.AgentTraceProfile::class)
class AgentTraceContractIT {

    /** The halt publishes the kill-switch event to the broker; InMemory keeps the boot hermetic. */
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("agent-kill-switch-events-out") +
                mapOf("quarkus.kafka.devservices.enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    /** The libs baseline gives this class its own boot (own recorder, dispatcher off). */
    class AgentTraceProfile : TraceContractProfile() {

        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            // Pin the sampler so the contract is hermetic: a parent-based sampler makes it a
            // function of an incoming traceparent's sampled flag the test does not control, and
            // an unsampled parent legitimately yields no spans (observed as 'observed span names
            // only: []'). Sampling policy is the deployment's concern, not the contract's.
            "quarkus.otel.traces.sampler" to "always_on",
            // The channel is deployment-gated off by default (AGENT_KILL_SWITCH_KAFKA_ENABLED);
            // without an enabled outgoing channel the halt's emitter cannot connect (SRMSG00019).
            // The InMemoryKafkaResource above already swaps the connector to the in-memory broker.
            "mp.messaging.outgoing.agent-kill-switch-events-out.enabled" to "true",
            // The kill-switch adapter is plain Agroal JDBC (no Hibernate ORM), which only emits
            // spans when telemetry is switched on; ORM-backed services get theirs from the
            // Hibernate instrumentation instead.
            "quarkus.datasource.jdbc.telemetry" to "true",
        )
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    @TestSecurity(user = "trace-it", roles = ["ROLE_ADMIN"])
    fun `halting a scope keeps the request and its kill-switch INSERT in one trace`() {
        Given {
            contentType("application/json")
            body(
                """{"scope":"trace-${UUID.randomUUID()}","reason":"trace contract probe"}""",
            )
        } When {
            post("/api/v1/admin/agents/halt")
        } Then {
            statusCode(200)
        }

        exporter.contract()
            .requiresSpan("POST /api/v1/admin/agents/halt")
            .requiresAttribute("POST /api/v1/admin/agents/halt", "http.response.status_code")
            // Agroal telemetry names JDBC spans `<operation> <schema>.<table>` (unlike the
            // Hibernate instrumentation's bare `<operation> <table>`), and only exists because
            // the profile enables quarkus.datasource.jdbc.telemetry above — this service's
            // adapters are plain JDBC, not ORM.
            .requiresSpan("INSERT openbank_agent_trace_it.agent_kill_switch")
            .requiresSameTrace(
                "POST /api/v1/admin/agents/halt",
                "INSERT openbank_agent_trace_it.agent_kill_switch",
            )
            .hasNoErrorSpan()
            .verifiedAs("agent-kill-switch-halt")
    }
}
