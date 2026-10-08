// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class AgentAuditInventoryTest {
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    @Suppress("LongMethod") // One two-database fixture exercises the approved-window and destination mismatch path.
    fun `campaign matches approved half-open window without claiming destination extras`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            createDestinationDatabase(pg)
            sourceConnection(pg).use { setup ->
                migrateSource(setup)
                insertCampaignSource(setup, first, "one", "2026-01-01T00:00:00Z", true)
                insertCampaignSource(setup, second, "two", "2026-01-01T00:00:00Z", true)
                insertCampaignSource(setup, UUID(0, 3), "outside", "2026-01-02T00:00:00Z", true)
            }
            destinationConnection(pg).use { setup ->
                migrateDestination(setup)
                insertDestination(setup, first, "one")
                insertDestination(setup, second, "two")
                insertDestination(setup, UUID(0, 3), "outside")
            }
            val window = campaignWindow()
            val result = AgentAuditInventory.reconcileCampaign(
                { sourceConnection(pg) },
                { destinationConnection(pg) },
                window,
                pageSize = 1,
            )
            assertThat(result.matched.count).isEqualTo(2)
            assertThat(result.destinationExtraCompletenessProven).isFalse()
            assertThatThrownBy {
                AgentAuditInventory.reconcileCampaign(
                    { sourceConnection(pg) },
                    { destinationConnection(pg) },
                    window.copy(expectedCount = 1),
                    pageSize = 1,
                )
            }.hasMessageContaining("approved count or manifest")
            assertThatThrownBy {
                AgentAuditInventory.reconcileCampaign(
                    { sourceConnection(pg) },
                    { destinationConnection(pg) },
                    window.copy(sourceManifestSha256 = "0".repeat(64)),
                    pageSize = 1,
                )
            }.hasMessageContaining("approved count or manifest")
            destinationConnection(pg).use { changed ->
                changed.createStatement().use { it.execute("DROP RULE no_update_audit ON audit_entries") }
                changed.prepareStatement("UPDATE audit_entries SET payload = ? WHERE entry_id = ?").use {
                    it.setString(1, "changed")
                    it.setObject(2, second)
                    it.executeUpdate()
                }
            }
            assertThatThrownBy {
                AgentAuditInventory.reconcileCampaign(
                    { sourceConnection(pg) },
                    { destinationConnection(pg) },
                    window,
                    pageSize = 1,
                )
            }.hasMessageContaining("identities or payload digests differ")
            destinationConnection(pg).use { changed ->
                changed.createStatement().use { it.execute("DROP RULE no_delete_audit ON audit_entries") }
                changed.prepareStatement("DELETE FROM audit_entries WHERE entry_id = ?").use {
                    it.setObject(1, second)
                    it.executeUpdate()
                }
            }
            assertThatThrownBy {
                AgentAuditInventory.reconcileCampaign(
                    { sourceConnection(pg) },
                    { destinationConnection(pg) },
                    window,
                    pageSize = 1,
                )
            }.hasMessageContaining("identities or payload digests differ")
        }
    }

    @Test
    fun `campaign rejects unpublished source row inside window`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            sourceConnection(pg).use { setup ->
                migrateSource(setup)
                insertCampaignSource(setup, first, "one", "2026-01-01T00:00:00Z", false)
            }
            assertThatThrownBy {
                AgentAuditInventory.reconcileCampaign(
                    { sourceConnection(pg) },
                    { sourceConnection(pg) },
                    campaignWindow(),
                    pageSize = 1,
                )
            }.hasMessageContaining("unpublished rows")
        }
    }

    private fun campaignWindow(): AgentAuditInventory.CampaignWindow {
        val time = Instant.parse("2026-01-01T00:00:00Z")
        val lines = listOf(first to "one", second to "two")
            .joinToString("") { (id, payload) -> "$time:$id:${sha(payload)}\n" }
        return AgentAuditInventory.CampaignWindow(
            time,
            Instant.parse("2026-01-02T00:00:00Z"),
            2,
            sha(lines),
        )
    }

    private fun insertCampaignSource(
        connection: Connection,
        id: UUID,
        payload: String,
        time: String,
        published: Boolean,
    ) {
        connection.prepareStatement(
            "INSERT INTO agent_audit_outbox (event_id, payload, created_at, published_at) " +
                "VALUES (?, ?, ?::timestamptz, ?::timestamptz)",
        ).use {
            it.setObject(1, id)
            it.setString(2, payload)
            it.setString(3, time)
            it.setString(4, if (published) time else null)
            it.executeUpdate()
        }
    }

    @Test
    fun `keyset export retains equal timestamp rows and hashes exact UTF-8 text`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            createDestinationDatabase(pg)
            sourceConnection(pg).use { setup ->
                migrateSource(setup)
                insertSource(setup, first, "  {\"note\":\"žluťoučký 🐈\"}  ")
                insertSource(setup, second, "{\"note\":\"two\"}")
            }
            destinationConnection(pg).use { setup ->
                migrateDestination(setup)
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
                    { sourceConnection(pg) },
                    { destinationConnection(pg) },
                    pageSize = 1,
                ).count,
            ).isEqualTo(2)

            destinationConnection(pg).use { changed ->
                changed.createStatement().use { it.execute("DROP RULE no_update_audit ON audit_entries") }
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
            createDestinationDatabase(pg)
            sourceConnection(pg).use { setup ->
                migrateSource(setup)
                insertSource(setup, first, "one")
                insertSource(setup, second, "two")
            }
            destinationConnection(pg).use { setup ->
                migrateDestination(setup)
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
            destinationConnection(pg).use { changed ->
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
            createDestinationDatabase(pg)
            sourceConnection(pg).use { setup ->
                migrateSource(setup)
                setup.prepareStatement("INSERT INTO agent_audit_outbox (event_id, payload) VALUES (?, ?)").use {
                    it.setObject(1, first)
                    it.setString(2, "payload")
                    it.executeUpdate()
                }
            }
            destinationConnection(pg).use { setup ->
                migrateDestination(setup)
                insertDestination(setup, first, "payload", "other-service")
            }
            assertThatThrownBy {
                AgentAuditInventory.reconcile(
                    { sourceConnection(pg) },
                    { destinationConnection(pg) },
                    pageSize = 1,
                )
            }.hasMessageContaining("attributed to another source")
        }
    }

    @Test
    fun `reconcile rejects aliases of one database and pre-opened transactions`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            sourceConnection(pg).use { setup ->
                migrateSource(setup)
                setup.prepareStatement("INSERT INTO agent_audit_outbox (event_id, payload) VALUES (?, ?)").use {
                    it.setObject(1, first)
                    it.setString(2, "payload")
                    it.executeUpdate()
                }
            }
            assertThatThrownBy {
                AgentAuditInventory.reconcile(
                    { sourceConnection(pg) },
                    { sourceConnection(pg) },
                    pageSize = 1,
                )
            }.hasMessageContaining("separate source and destination databases")
            assertThatThrownBy {
                AgentAuditInventory.reconcile(
                    { snapshotConnection(pg, AgentAuditInventory.Side.SOURCE) },
                    { sourceConnection(pg) },
                    pageSize = 1,
                )
            }.hasMessageContaining("fresh connection")
        }
    }

    @Test
    fun `collect rejects server transaction modes that are not read-only repeatable-read`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { pg ->
            pg.start()
            sourceConnection(pg).use { connection ->
                connection.autoCommit = false
                assertThatThrownBy { AgentAuditInventory.collect(connection, AgentAuditInventory.Side.SOURCE) }
                    .hasMessageContaining("read-only repeatable-read")
            }
            snapshotConnection(pg, AgentAuditInventory.Side.SOURCE).use { connection ->
                connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED") }
                // Simulate a pool reporting stale JDBC flags. The server setting remains authoritative.
                val staleClientView = Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, arguments ->
                    if (method.name == "getTransactionIsolation") {
                        Connection.TRANSACTION_REPEATABLE_READ
                    } else {
                        method.invoke(connection, *(arguments ?: emptyArray()))
                    }
                } as Connection
                assertThatThrownBy { AgentAuditInventory.collect(staleClientView, AgentAuditInventory.Side.SOURCE) }
                    .hasMessageContaining("server transaction is not read-only repeatable-read")
            }
        }
    }

    private fun snapshot(
        pg: PostgreSQLContainer<Nothing>,
        side: AgentAuditInventory.Side,
        sourceIds: List<UUID> = emptyList(),
    ): List<AgentAuditEvidenceIdentity> = snapshotConnection(pg, side).use { connection ->
        AgentAuditInventory.collect(connection, side, pageSize = 1, sourceIds = sourceIds)
    }

    private fun snapshotConnection(pg: PostgreSQLContainer<Nothing>, side: AgentAuditInventory.Side): Connection =
        (if (side == AgentAuditInventory.Side.SOURCE) sourceConnection(pg) else destinationConnection(pg)).also {
            it.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            it.isReadOnly = true
            it.autoCommit = false
        }

    private fun sourceConnection(pg: PostgreSQLContainer<Nothing>): Connection =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    private fun migrateSource(connection: Connection) {
        listOf(4, 6, 7).forEach { version ->
            val migration = File("src/main/resources/db/migration").listFiles()!!
                .single { it.name.startsWith("V${version}__") }
            connection.createStatement().use { it.execute(migration.readText()) }
        }
    }

    private fun migrateDestination(connection: Connection) {
        connection.createStatement().use { it.execute("CREATE ROLE openbank") }
        listOf(1, 2, 5).forEach { version ->
            val migration = File("../openbank-audit-service/src/main/resources/db/migration").listFiles()!!
                .single { it.name.startsWith("V${version}__") }
            connection.createStatement().use { it.execute(migration.readText()) }
        }
    }

    private fun destinationConnection(pg: PostgreSQLContainer<Nothing>): Connection = DriverManager.getConnection(
        pg.jdbcUrl.substringBeforeLast('/') + "/audit_inventory_destination",
        pg.username,
        pg.password,
    )

    private fun createDestinationDatabase(pg: PostgreSQLContainer<Nothing>) {
        sourceConnection(pg).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE audit_inventory_destination") }
        }
    }

    private fun insertSource(connection: Connection, id: UUID, payload: String) {
        connection.prepareStatement(
            "INSERT INTO agent_audit_outbox (event_id, payload, created_at) VALUES (?, ?, '2026-01-01T00:00:00Z')",
        ).use {
            it.setObject(1, id)
            it.setString(2, payload)
            it.executeUpdate()
        }
    }

    private fun insertDestination(connection: Connection, id: UUID, payload: String, source: String = "agent-service") {
        connection.prepareStatement(
            "INSERT INTO audit_entries (entry_id, event_type, aggregate_type, aggregate_id, payload, " +
                "source_service, occurred_at) VALUES (?, 'agent.audit', 'agent', 'test', ?, ?, NOW())",
        ).use {
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
