// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.integration

import com.openbank.agent.infrastructure.audit.AgentAuditOutbox
import com.openbank.agent.infrastructure.audit.AgentAuditReplayStore
import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Exercises the real V4 schema: a duplicate producer id persists once and remains claimable. */
@QuarkusTest
// restrictToAnnotatedClass=true — without it, PostgresTestResource's injected
// `agent.model.openai.api-key=test-not-used` placeholder leaks into other @QuarkusTest classes
// sharing the same test JVM whose own @TestProfile expects a different value (e.g.
// ModelGatewayRoutingOverrideTest), the same defect class ProposalApiIT and
// McpEndpointRoutingIT already guard against.
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class AgentAuditOutboxIT {
    @Inject lateinit var outbox: AgentAuditOutbox

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var replayStore: AgentAuditReplayStore

    @Test
    fun `V4 durable handoff deduplicates producer event id before dispatch`() {
        val eventId = UUID.randomUUID()
        outbox.enqueue(eventId, "{\"eventId\":\"$eventId\"}")
        outbox.enqueue(eventId, "{\"eventId\":\"$eventId\"}")

        val claimed = outbox.claim(25)

        assertThat(claimed).containsExactly(AgentAuditOutbox.Claimed(eventId, "{\"eventId\":\"$eventId\"}"))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*), MAX(publish_attempts) FROM agent_audit_outbox WHERE event_id = ?",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getLong(1)).isEqualTo(1)
                    assertThat(rs.getInt(2)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `replay cursor resumes after ACK and rejects changed source payload`() {
        val eventId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val createdAt = Instant.parse("2025-01-01T12:00:00Z")
        val payload = "{\"eventId\":\"$eventId\",\"sourceService\":\"agent-service\"}"
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO agent_audit_outbox (event_id, payload, created_at, published_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.setString(2, payload)
                statement.setTimestamp(3, Timestamp.from(createdAt))
                statement.setTimestamp(4, Timestamp.from(createdAt.plusSeconds(1)))
                statement.executeUpdate()
            }
            val from = createdAt.minusSeconds(1)
            val until = createdAt.plusSeconds(2)
            val inventory = replayStore.inventory(connection, from, until)
            assertThat(inventory).isEqualTo(AgentAuditReplayStore.Inventory(1, 0))
            val rowManifest = AgentAuditReplayStore.sha256(
                "$createdAt:$eventId:${AgentAuditReplayStore.sha256(payload)}\n",
            )
            val campaign = replayStore.campaign(connection, campaignId, from, until, 10, inventory.total, rowManifest)
            assertThat(replayStore.reserveBatch(connection, campaignId)).isTrue()
            assertThat(replayStore.reserveBatch(connection, campaignId)).isFalse()
            val row = replayStore.next(connection, campaign, 10).single { it.eventId == eventId }
            assertThat(row.payload).isEqualTo(payload)
            // A process crash after send but before checkpoint must see the same source row.
            assertThat(replayStore.next(connection, campaign, 10)).contains(row)
            replayStore.checkpoint(connection, campaign, null, row, 10)
            val resumed = replayStore.campaign(connection, campaignId, from, until, 10, inventory.total, rowManifest)
            replayStore.verifyCursor(connection, resumed.cursor)
            assertThat(replayStore.next(connection, resumed, 10)).doesNotContain(row)
            assertScopeAndSourceImmutable(connection, campaignId, from, until, rowManifest, eventId, payload)
        }
    }

    private fun assertScopeAndSourceImmutable(
        connection: Connection,
        campaignId: UUID,
        from: Instant,
        until: Instant,
        manifest: String,
        eventId: UUID,
        payload: String,
    ) {
        assertThatThrownBy {
            replayStore.campaign(connection, campaignId, from, until, 11, 1, manifest)
        }.hasMessageContaining("campaign scope changed")
        assertThatThrownBy {
            replayStore.campaign(connection, campaignId, from, until, 10, 1, "different-manifest")
        }.hasMessageContaining("campaign scope changed")
        assertThatThrownBy {
            connection.prepareStatement(
                "UPDATE agent_audit_outbox SET payload = ? WHERE event_id = ?",
            ).use { statement ->
                statement.setString(1, payload.replace("agent-service", "different-source"))
                statement.setObject(2, eventId)
                statement.executeUpdate()
            }
        }.hasMessageContaining("source fields are immutable")
    }
}
