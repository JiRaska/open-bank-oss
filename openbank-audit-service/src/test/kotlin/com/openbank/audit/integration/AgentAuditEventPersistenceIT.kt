// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.application.AgentAuditConsumer
import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant

/**
 * Issue #6191 — the half of the D5 provenance path neither existing test reaches: an
 * `AgentAuditEventEnvelope` as agent-service actually puts it on the wire becomes a ROW in the
 * append-only, hash-chained `audit_entries`.
 *
 * **Why this is not covered already.** `DurableAgentAuditPublisherTest` (agent-service) verifies
 * the producer against a MOCKED `AgentAuditOutbox` — a mock cannot establish that anything was
 * stored. `AgentAuditOutboxIT` (agent-service) proves the outbox row survives the real V4 schema
 * but stops at the local table. `AgentAuditRedeliveryIT` (this module) hand-builds an
 * `AuditEntry` and calls `AuditRepository.save` twice — it never parses a producer payload, so it
 * cannot see a field the producer spells one way and the consumer reads another. Between them the
 * two modules were each self-consistent and untested against each other, which is exactly how a
 * producer/consumer pair drifts in silence.
 *
 * **Why the row is read over plain JDBC.** Reading it back through the same reactive
 * `AuditRepository` that wrote it asks the code under test whether it did its job. This opens its
 * own connection to the Testcontainers Postgres and selects the row.
 *
 * **What this does and does not drive.** There is no Kafka Testcontainer in this repo, so this is
 * not a literal broker round trip: it is the real CDI-managed [AgentAuditConsumer] — including its
 * ack-after-persist ordering — and the real database write. The transport itself (topic, mTLS,
 * ACLs) is manifest, verified by review of `openbank-infra/gitops/components/agent/` rather than
 * executed here. Stated plainly rather than implied by a green test.
 *
 * **The transport flag is not touched.** `AGENT_AUDIT_KAFKA_ENABLED` stays `false`, #6209's
 * deliberate rollout choice. Nothing here needs it: the consumer is driven directly with the
 * payload the dispatcher would have sent, so this test is about the wire shape, not the rollout.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AgentAuditEventPersistenceIT {

    @Inject
    lateinit var consumer: AgentAuditConsumer

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    /**
     * The flat JSON `DurableAgentAuditPublisher.publish` produces: the caller's free-form
     * `AuditEvent.payload` merged UNDER the fixed `AgentAuditEventEnvelope` fields (`Map +`
     * semantics — the envelope wins a key collision). Every key and spelling below is read off
     * `AgentAuditEventEnvelope` in `JdbcAgentAuditOutbox.kt`, not invented here; the two modules
     * cannot share code, so rename a field on either side and this goes red.
     */
    private fun agentAuditEnvelope(eventId: String, actorId: String, occurredAt: Instant) = """
        {
          "tool": "get_account",
          "policy_decision": "DENY",
          "eventId": "$eventId",
          "eventType": "agent.mcp.tool_call",
          "aggregateType": "mcp.tool",
          "aggregateId": "acc-42",
          "actorId": "$actorId",
          "actorType": "AI_AGENT",
          "sourceService": "agent-service",
          "correlationId": "trace-$eventId",
          "occurredAt": "$occurredAt",
          "channel": "mcp",
          "actChain": [],
          "sessionId": "sess-$eventId",
          "result": "DENIED"
        }
    """.trimIndent()

    @Test
    fun `an agent audit envelope lands in audit_entries attributed to agent-service`() {
        val eventId = "9f1d0b9a-3c2e-4d51-8a77-${System.nanoTime().toString().takeLast(12).padStart(12, '0')}"
        val actorId = "agent:rca-${System.nanoTime()}"
        val occurredAt = Instant.parse("2026-08-21T12:34:56Z")

        onEventLoop {
            consumer.consume(Message.of(agentAuditEnvelope(eventId, actorId, occurredAt)))
        }

        jdbc().use { c ->
            c.prepareStatement(
                """
                select entry_id, event_type, aggregate_type, aggregate_id, actor_id, actor_type,
                       source_service, source_service_source, correlation_id, session_id,
                       occurred_at, occurred_at_source
                  from audit_entries where actor_id = ?
                """.trimIndent(),
            ).use { st ->
                st.setString(1, actorId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).describedAs("a row for the agent action must exist").isTrue()
                    // The producer's event id IS the row id — that is what makes an at-least-once
                    // Kafka redelivery idempotent (AgentAuditRedeliveryIT proves the second write
                    // is a no-op; this proves the id it de-duplicates on is the producer's).
                    assertThat(rs.getString("entry_id")).isEqualTo(eventId)
                    assertThat(rs.getString("event_type")).isEqualTo("agent.mcp.tool_call")
                    // Uppercased by AuditConsumer on purpose (#4553): the column records which
                    // resolution path fired, and a producer's verbatim spelling would split the
                    // series. The envelope sends AuditEvent.resourceType here.
                    assertThat(rs.getString("aggregate_type")).isEqualTo("MCP.TOOL")
                    assertThat(rs.getString("actor_type")).isEqualTo("AI_AGENT")
                    // The producing service's own claim, not a topic-derived guess — and note
                    // AgentAuditConsumer passes EventAddress.NONE, so there is no topic to fall
                    // back to: if the producer ever stopped sending `sourceService` this row
                    // would be the "unknown" sentinel. The value is chain-hashed into
                    // record_hash and audit_entries is append-only at the DB (no_update_audit is
                    // DO INSTEAD NOTHING — an UPDATE affects zero rows and reports success), so
                    // it can never be corrected afterwards.
                    assertThat(rs.getString("source_service")).isEqualTo("agent-service")
                    assertThat(rs.getString("source_service_source")).isEqualTo(AttributionSource.EVENT.name)
                    assertThat(rs.getString("correlation_id")).isEqualTo("trace-$eventId")
                    assertThat(rs.getString("session_id")).isEqualTo("sess-$eventId")
                    // Business time, not ingest time. Asserted as an exact instant rather than
                    // non-nullity: Instant.EPOCH passes isNotNull(), and an INGEST fallback would
                    // pass any recency window a fast test could write.
                    assertThat(rs.getTimestamp("occurred_at").toInstant()).isEqualTo(occurredAt)
                    assertThat(rs.getString("occurred_at_source")).isEqualTo(OccurredAtSource.EVENT.name)
                    assertThat(rs.next()).describedAs("exactly one row for one event").isFalse()
                }
            }
        }
    }

    /**
     * The fix for #6318, driven through the real CDI consumer and read back over a plain JDBC
     * connection that shares no code with the write path.
     *
     * This test previously asserted the `"unknown"` sentinel — deliberately, to pin the defect
     * rather than agree with it silently. `AuditConsumer` took `aggregateType` from the producer
     * verbatim while deriving `aggregateId` through `inferAggregateId`'s fixed business-id chain,
     * and the agent envelope spells its resource `aggregateId`, so the chain fell through and the
     * row was not joinable to the resource the agent acted on. `aggregateId` is now read from the
     * envelope first, with the chain as fallback.
     *
     * The flip of this assertion IS the regression test: revert the one production line and this
     * goes red on the exact value.
     */
    @Test
    fun `the envelope aggregateId reaches the row`() {
        val eventId = "3ab7c410-55de-4e0a-9b12-${System.nanoTime().toString().takeLast(12).padStart(12, '0')}"
        val actorId = "agent:aggid-${System.nanoTime()}"
        val before = Instant.now().minusSeconds(1)

        onEventLoop {
            consumer.consume(Message.of(agentAuditEnvelope(eventId, actorId, Instant.parse("2026-08-21T12:34:56Z"))))
        }

        jdbc().use { c ->
            c.prepareStatement("select aggregate_id, recorded_at from audit_entries where actor_id = ?").use { st ->
                st.setString(1, actorId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("aggregate_id"))
                        .describedAs("the envelope sends aggregateId=acc-42 and the row must carry it")
                        .isEqualTo("acc-42")
                    // Recency, never non-nullity: an Instant.EPOCH default passes isNotNull() and
                    // every test agrees with it. This bounds the ingest stamp on both sides.
                    assertThat(rs.getTimestamp("recorded_at").toInstant())
                        .isBetween(before, Instant.now().plusSeconds(1))
                }
            }
        }
    }

    /**
     * The other half of the precedence decision: with no envelope `aggregateId`, the inference
     * chain still runs. Without this, envelope-first could have been implemented as
     * envelope-ONLY and every producer that has never sent the key — 20 of the 27 subscribed
     * topics — would have silently regressed from a correct inferred id to the sentinel, with
     * nothing red to say so.
     */
    @Test
    fun `with no envelope aggregateId the inference chain still supplies one`() {
        val eventId = "5c2e88a1-0d44-4f37-9e60-${System.nanoTime().toString().takeLast(12).padStart(12, '0')}"
        val actorId = "agent:infer-${System.nanoTime()}"
        val accountId = "acct-${System.nanoTime()}"
        val payload = agentAuditEnvelope(eventId, actorId, Instant.parse("2026-08-21T12:34:56Z"))
            .replace(""""aggregateId": "acc-42",""", """"accountId": "$accountId",""")

        onEventLoop { consumer.consume(Message.of(payload)) }

        jdbc().use { c ->
            c.prepareStatement("select aggregate_id from audit_entries where actor_id = ?").use { st ->
                st.setString(1, actorId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("aggregate_id")).isEqualTo(accountId)
                }
            }
        }
    }

    /**
     * The third outcome, which the `"unknown"` sentinel alone cannot distinguish from the second:
     * neither the producer nor the chain names a resource. The row still lands — a degraded audit
     * entry beats no audit entry — and `openbank.audit.aggregate.id.provenance{provenance=ABSENT}`
     * is what makes this state countable rather than invisible.
     */
    @Test
    fun `with neither an envelope aggregateId nor an inferable id the row keeps the sentinel`() {
        val eventId = "7d3f19c2-6b81-4a95-8c04-${System.nanoTime().toString().takeLast(12).padStart(12, '0')}"
        val actorId = "agent:absent-${System.nanoTime()}"
        val payload = agentAuditEnvelope(eventId, actorId, Instant.parse("2026-08-21T12:34:56Z"))
            .replace(""""aggregateId": "acc-42",""", "")

        onEventLoop { consumer.consume(Message.of(payload)) }

        jdbc().use { c ->
            c.prepareStatement("select aggregate_id from audit_entries where actor_id = ?").use { st ->
                st.setString(1, actorId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("aggregate_id")).isEqualTo("unknown")
                }
            }
        }
    }
}
