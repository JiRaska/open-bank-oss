// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.integration

import com.openbank.agent.infrastructure.audit.AgentAuditOutbox
import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
}
