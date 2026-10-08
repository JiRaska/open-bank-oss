// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class AgentAuditInventoryTest {
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `keyset export retains equal timestamp rows and hashes exact UTF-8 text`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { setup ->
                setup.createStatement().use { sql ->
                    sql.execute(
                        "CREATE TABLE agent_audit_outbox " +
                            "(event_id UUID PRIMARY KEY, payload TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL)",
                    )
                    sql.execute(
                        "CREATE TABLE audit_entries " +
                            "(entry_id UUID PRIMARY KEY, payload TEXT NOT NULL, source_service TEXT NOT NULL)",
                    )
                }
                insertSource(setup, first, "  {\"note\":\"žluťoučký 🐈\"}  ")
                insertSource(setup, second, "{\"note\":\"two\"}")
                insertDestination(setup, first, "  {\"note\":\"žluťoučký 🐈\"}  ")
                insertDestination(setup, second, "{\"note\":\"two\"}")
            }
            val source = snapshot(pg, AgentAuditInventory.Side.SOURCE)
            val destination = snapshot(pg, AgentAuditInventory.Side.DESTINATION, source.map { it.eventId })
            assertThat(source.map { it.eventId }).containsExactly(first, second)
            assertThat(source.first().payloadSha256).isEqualTo(sha("  {\"note\":\"žluťoučký 🐈\"}  "))
            assertThat(AgentAuditDestinationReconciliation.compare(source, destination).count).isEqualTo(2)
            assertThat(
                AgentAuditInventory.reconcile(
                    { snapshotConnection(pg) },
                    { snapshotConnection(pg) },
                    pageSize = 1,
                ).count,
            ).isEqualTo(2)

            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { changed ->
                changed.prepareStatement("UPDATE audit_entries SET payload = ? WHERE entry_id = ?").use {
                    it.setString(1, "{\"note\":\"two\"} ")
                    it.setObject(2, second)
                    it.executeUpdate()
                }
            }
            val changed = snapshot(pg, AgentAuditInventory.Side.DESTINATION, source.map { it.eventId })
            assertThatThrownBy { AgentAuditInventory.verifyUnchanged(destination, changed) }
                .hasMessageContaining("changed during collection")
            assertThatThrownBy { AgentAuditDestinationReconciliation.compare(source, changed) }
                .hasMessageContaining("differ")
        }
    }

    @Test
    fun `extra and substituted destination identities fail at equal count`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { setup ->
                setup.createStatement().use { sql ->
                    sql.execute(
                        "CREATE TABLE agent_audit_outbox " +
                            "(event_id UUID PRIMARY KEY, payload TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL)",
                    )
                    sql.execute(
                        "CREATE TABLE audit_entries " +
                            "(entry_id UUID PRIMARY KEY, payload TEXT NOT NULL, source_service TEXT NOT NULL)",
                    )
                }
                insertSource(setup, first, "one")
                insertSource(setup, second, "two")
                insertDestination(setup, first, "one")
                insertDestination(setup, UUID(0, 3), "three")
                insertDestination(setup, UUID(0, 4), "not agent", "other-service")
            }
            val source = snapshot(pg, AgentAuditInventory.Side.SOURCE)
            val destination = snapshot(pg, AgentAuditInventory.Side.DESTINATION, source.map { it.eventId })
            assertThat(source).hasSize(2)
            assertThat(destination).hasSize(2)
            assertThatThrownBy { AgentAuditDestinationReconciliation.compare(source, destination) }
                .hasMessageContaining("differ")
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { changed ->
                insertDestination(changed, second, "two")
            }
            assertThatThrownBy {
                AgentAuditDestinationReconciliation.compare(
                    source,
                    snapshot(pg, AgentAuditInventory.Side.DESTINATION, source.map { it.eventId }),
                )
            }.hasMessageContaining("differ")
        }
    }

    @Test
    fun `misattributed destination identity is rejected instead of hidden`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { setup ->
                setup.createStatement().use { sql ->
                    sql.execute("CREATE TABLE agent_audit_outbox (event_id UUID PRIMARY KEY, payload TEXT NOT NULL)")
                    sql.execute(
                        "CREATE TABLE audit_entries " +
                            "(entry_id UUID PRIMARY KEY, payload TEXT NOT NULL, source_service TEXT NOT NULL)",
                    )
                }
                setup.prepareStatement("INSERT INTO agent_audit_outbox VALUES (?, ?)").use {
                    it.setObject(1, first)
                    it.setString(2, "payload")
                    it.executeUpdate()
                }
                insertDestination(setup, first, "payload", "other-service")
            }
            assertThatThrownBy {
                AgentAuditInventory.reconcile(
                    { snapshotConnection(pg) },
                    { snapshotConnection(pg) },
                    pageSize = 1,
                )
            }.hasMessageContaining("attributed to another source")
        }
    }

    private fun snapshot(
        pg: PostgreSQLContainer<Nothing>,
        side: AgentAuditInventory.Side,
        sourceIds: List<UUID> = emptyList(),
    ): List<AgentAuditEvidenceIdentity> = snapshotConnection(pg).use { connection ->
        AgentAuditInventory.collect(connection, side, pageSize = 1, sourceIds = sourceIds)
    }

    private fun snapshotConnection(pg: PostgreSQLContainer<Nothing>): Connection =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).also {
            it.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            it.isReadOnly = true
            it.autoCommit = false
        }

    private fun insertSource(connection: Connection, id: UUID, payload: String) {
        connection.prepareStatement("INSERT INTO agent_audit_outbox VALUES (?, ?, '2026-01-01T00:00:00Z')").use {
            it.setObject(1, id)
            it.setString(2, payload)
            it.executeUpdate()
        }
    }

    private fun insertDestination(connection: Connection, id: UUID, payload: String, source: String = "agent-service") {
        connection.prepareStatement("INSERT INTO audit_entries VALUES (?, ?, ?)").use {
            it.setObject(1, id)
            it.setString(2, payload)
            it.setString(3, source)
            it.executeUpdate()
        }
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
