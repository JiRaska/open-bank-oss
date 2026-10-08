// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

/** Read-only, database-independent evidence collection. The caller supplies two distinct connections. */
object AgentAuditInventory {
    enum class Side { SOURCE, DESTINATION }

    /**
     * Collects both stores twice using fresh, independent snapshots. A mismatch never exposes a
     * payload or a record identity. This is an observation, not a cross-database atomic snapshot:
     * the operator must still fence writes for a final replay cutover.
     */
    fun reconcile(
        sourceConnection: () -> Connection,
        destinationConnection: () -> Connection,
        pageSize: Int = 500,
    ): AgentAuditDestinationReconciliation.Result {
        val source = sourceConnection().use { collect(it, Side.SOURCE, pageSize) }
        val sourceIds = source.map { it.eventId }
        val destination = destinationConnection().use { collect(it, Side.DESTINATION, pageSize, sourceIds) }
        val result = AgentAuditDestinationReconciliation.compare(source, destination)
        verifyUnchanged(source, sourceConnection().use { collect(it, Side.SOURCE, pageSize) })
        verifyUnchanged(destination, destinationConnection().use { collect(it, Side.DESTINATION, pageSize, sourceIds) })
        return result
    }

    /**
     * The caller configures read-only repeatable-read before the transaction's first query and
     * owns its lifecycle; this method never commits or changes it.
     */
    fun collect(
        connection: Connection,
        side: Side,
        pageSize: Int = 500,
        sourceIds: List<UUID> = emptyList(),
    ): List<AgentAuditEvidenceIdentity> {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Invalid audit inventory page size" }
        require(
            !connection.autoCommit &&
                connection.isReadOnly &&
                connection.transactionIsolation == Connection.TRANSACTION_REPEATABLE_READ,
        ) { "Audit inventory requires a read-only repeatable-read transaction" }

        if (side == Side.DESTINATION) {
            require(sourceIds.isNotEmpty()) { "Destination inventory requires source identities" }
        }
        val (idColumn, table, filter) = when (side) {
            Side.SOURCE -> Triple("event_id", "agent_audit_outbox", "")
            Side.DESTINATION -> Triple(
                "entry_id",
                "audit_entries",
                " AND (source_service = 'agent-service' OR entry_id = ANY(?))",
            )
        }
        val result = mutableListOf<AgentAuditEvidenceIdentity>()
        var cursor: UUID? = null
        while (true) {
            // UUID keyset pagination does not lose rows sharing the same created_at timestamp.
            val sql = "SELECT $idColumn, payload" +
                (if (side == Side.DESTINATION) ", source_service" else "") +
                " FROM $table WHERE (?::uuid IS NULL OR $idColumn > ?)" +
                "$filter ORDER BY $idColumn LIMIT ?"
            val page = connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, cursor)
                statement.setObject(2, cursor)
                if (side == Side.DESTINATION) {
                    val ids = connection.createArrayOf("uuid", sourceIds.toTypedArray())
                    try {
                        statement.setArray(3, ids)
                        statement.setInt(4, pageSize)
                        statement.executeQuery().use { rows -> readPage(rows, side) }
                    } finally {
                        ids.free()
                    }
                } else {
                    statement.setInt(3, pageSize)
                    statement.executeQuery().use { rows -> readPage(rows, side) }
                }
            }
            if (page.isEmpty()) break
            check(cursor != page.last().eventId) { "Audit inventory did not advance" }
            result.addAll(page)
            cursor = page.last().eventId
        }
        return result
    }

    /** A second collection in fresh snapshots detects additions, removals, or payload edits during export. */
    fun verifyUnchanged(first: List<AgentAuditEvidenceIdentity>, second: List<AgentAuditEvidenceIdentity>) {
        check(first == second) { "Audit inventory changed during collection" }
    }

    private fun readPage(rows: java.sql.ResultSet, side: Side): List<AgentAuditEvidenceIdentity> = buildList {
        while (rows.next()) {
            val id = rows.getObject(1, UUID::class.java)
            check(id != null) { "Audit inventory contains a null identity" }
            if (side == Side.DESTINATION) {
                val sourceService = rows.getString(3)
                check(sourceService == "agent-service") {
                    "Audit destination identity is attributed to another source"
                }
            }
            val rawPayload = rows.getString(2)
            check(rawPayload != null) { "Audit inventory contains a null payload" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(rawPayload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            add(AgentAuditEvidenceIdentity(id, digest))
        }
    }

    private const val MAX_PAGE_SIZE = 5000
}
