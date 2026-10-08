// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Read-only, database-independent evidence collection. The caller supplies two distinct connections. */
// MagicNumber: ResultSet/PreparedStatement positions are fixed by the SQL projections above.
@Suppress("MagicNumber")
object AgentAuditInventory {
    enum class Side { SOURCE, DESTINATION }

    /** The same half-open window and source manifest approved for historical replay. */
    data class CampaignWindow(
        val from: Instant,
        val until: Instant,
        val expectedCount: Int,
        val sourceManifestSha256: String,
    )

    /** Destination extras cannot be assigned to a window without destination campaign provenance. */
    data class CampaignResult(
        val matched: AgentAuditDestinationReconciliation.Result,
        val destinationExtraCompletenessProven: Boolean = false,
    )

    fun reconcileCampaign(
        sourceConnection: () -> Connection,
        destinationConnection: () -> Connection,
        window: CampaignWindow,
        pageSize: Int = 500,
    ): CampaignResult {
        require(window.from < window.until && window.until <= Instant.now()) { "Invalid audit campaign window" }
        require(
            window.from == window.from.truncatedTo(java.time.temporal.ChronoUnit.MICROS) &&
                window.until == window.until.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        ) { "Audit campaign window exceeds database timestamp precision" }
        require(window.expectedCount in 1..MAX_PAGE_SIZE) { "Invalid audit campaign count" }
        require(window.sourceManifestSha256.matches(SHA256)) { "Invalid audit campaign manifest" }
        val source = campaignSnapshot(sourceConnection, window, pageSize)
        verifyApprovedSource(source.rows, source.manifest, window)
        val sourceIds = source.rows.map { it.eventId }
        val destination = snapshot(
            destinationConnection,
            Side.DESTINATION,
            pageSize,
            sourceIds,
            distinctFrom = source.database,
            includeAttributedExtras = false,
        )
        val matched = AgentAuditDestinationReconciliation.compare(source.rows, destination.rows)
        val sourceAgain = campaignSnapshot(sourceConnection, window, pageSize, expectedDatabase = source.database)
        val destinationAgain = snapshot(
            destinationConnection,
            Side.DESTINATION,
            pageSize,
            sourceIds,
            expectedDatabase = destination.database,
            includeAttributedExtras = false,
        )
        verifyApprovedSource(sourceAgain.rows, sourceAgain.manifest, window)
        verifyUnchanged(source.rows, sourceAgain.rows)
        verifyUnchanged(destination.rows, destinationAgain.rows)
        return CampaignResult(matched)
    }

    private fun verifyApprovedSource(rows: List<AgentAuditEvidenceIdentity>, manifest: String, window: CampaignWindow) {
        check(rows.size == window.expectedCount && manifest == window.sourceManifestSha256) {
            "Audit campaign source differs from approved count or manifest"
        }
    }

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
        val source = snapshot(sourceConnection, Side.SOURCE, pageSize)
        val sourceIds = source.rows.map { it.eventId }
        val destination = snapshot(
            destinationConnection,
            Side.DESTINATION,
            pageSize,
            sourceIds,
            distinctFrom = source.database,
        )
        val result = AgentAuditDestinationReconciliation.compare(source.rows, destination.rows)
        val sourceAgain = snapshot(sourceConnection, Side.SOURCE, pageSize, expectedDatabase = source.database)
        val destinationAgain = snapshot(
            destinationConnection,
            Side.DESTINATION,
            pageSize,
            sourceIds,
            expectedDatabase = destination.database,
        )
        verifyUnchanged(source.rows, sourceAgain.rows)
        verifyUnchanged(destination.rows, destinationAgain.rows)
        return result
    }

    private data class Snapshot(val database: String, val rows: List<AgentAuditEvidenceIdentity>)
    private data class CampaignSnapshot(
        val database: String,
        val rows: List<AgentAuditEvidenceIdentity>,
        val manifest: String,
    )

    private fun campaignSnapshot(
        factory: () -> Connection,
        window: CampaignWindow,
        pageSize: Int,
        expectedDatabase: String? = null,
    ): CampaignSnapshot = factory().use { connection ->
        require(connection.autoCommit) { "Audit inventory requires a fresh connection" }
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.isReadOnly = true
        connection.autoCommit = false
        val database = serverDatabase(connection)
        check(expectedDatabase == null || database == expectedDatabase) {
            "Audit inventory database identity changed during collection"
        }
        val (rows, manifest) = collectCampaignSource(connection, window, pageSize)
        CampaignSnapshot(database, rows, manifest)
    }

    private fun snapshot(
        factory: () -> Connection,
        side: Side,
        pageSize: Int,
        sourceIds: List<UUID> = emptyList(),
        expectedDatabase: String? = null,
        distinctFrom: String? = null,
        includeAttributedExtras: Boolean = true,
    ): Snapshot = factory().use { connection ->
        // A pre-opened transaction may already hold an old snapshot; refuse one even if its
        // eventual JDBC flags look correct. The server check below verifies the effective mode.
        require(connection.autoCommit) { "Audit inventory requires a fresh connection" }
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.isReadOnly = true
        connection.autoCommit = false
        val database = serverDatabase(connection)
        check(expectedDatabase == null || database == expectedDatabase) {
            "Audit inventory database identity changed during collection"
        }
        check(distinctFrom == null || database != distinctFrom) {
            "Audit inventory requires separate source and destination databases"
        }
        Snapshot(database, collect(connection, side, pageSize, sourceIds, includeAttributedExtras))
    }

    /**
     * The caller configures read-only repeatable-read before the transaction's first query and
     * owns its lifecycle; this method never commits or changes it.
     */
    // NestedBlockDepth: the connection, statement, SQL array, and result set must close in order.
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    fun collect(
        connection: Connection,
        side: Side,
        pageSize: Int = 500,
        sourceIds: List<UUID> = emptyList(),
        includeAttributedExtras: Boolean = true,
    ): List<AgentAuditEvidenceIdentity> {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Invalid audit inventory page size" }
        require(
            !connection.autoCommit &&
                connection.isReadOnly &&
                connection.transactionIsolation == Connection.TRANSACTION_REPEATABLE_READ,
        ) { "Audit inventory requires a read-only repeatable-read transaction" }
        serverDatabase(connection)

        if (side == Side.DESTINATION) {
            require(sourceIds.isNotEmpty()) { "Destination inventory requires source identities" }
        }
        val (idColumn, table, filter) = when (side) {
            Side.SOURCE -> Triple("event_id", "agent_audit_outbox", "")
            Side.DESTINATION -> Triple(
                "entry_id",
                "audit_entries",
                if (includeAttributedExtras) {
                    " AND (source_service = 'agent-service' OR entry_id = ANY(?))"
                } else {
                    " AND entry_id = ANY(?)"
                },
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

    /** Source ordering and manifest bytes match AgentAuditHistoricalReplay.validateEntireWindow. */
    private fun collectCampaignSource(
        connection: Connection,
        window: CampaignWindow,
        pageSize: Int,
    ): Pair<List<AgentAuditEvidenceIdentity>, String> {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Invalid audit inventory page size" }
        val rows = mutableListOf<AgentAuditEvidenceIdentity>()
        val digest = MessageDigest.getInstance("SHA-256")
        var cursorTime: Instant? = null
        var cursorId: UUID? = null
        while (true) {
            val page = connection.prepareStatement(
                "SELECT created_at, event_id, payload, published_at FROM agent_audit_outbox " +
                    "WHERE created_at >= ? AND created_at < ? " +
                    "AND (?::timestamptz IS NULL OR (created_at, event_id) > (?::timestamptz, ?::uuid)) " +
                    "ORDER BY created_at, event_id LIMIT ?",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(window.from))
                statement.setTimestamp(2, Timestamp.from(window.until))
                statement.setTimestamp(3, cursorTime?.let(Timestamp::from))
                statement.setTimestamp(4, cursorTime?.let(Timestamp::from))
                statement.setObject(5, cursorId)
                statement.setInt(6, pageSize)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            check(result.getTimestamp(4) != null) { "Audit campaign source contains unpublished rows" }
                            val time = result.getTimestamp(1).toInstant()
                            val id = result.getObject(2, UUID::class.java)
                            val payload = result.getString(3)
                            check(id != null && payload != null) { "Audit campaign source is incomplete" }
                            add(Triple(time, id, payload))
                        }
                    }
                }
            }
            if (page.isEmpty()) break
            page.forEach { (time, id, payload) ->
                val payloadDigest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                rows.add(AgentAuditEvidenceIdentity(id, payloadDigest))
                digest.update("$time:$id:$payloadDigest\n".toByteArray(Charsets.UTF_8))
            }
            cursorTime = page.last().first
            cursorId = page.last().second
            check(rows.size <= MAX_PAGE_SIZE) { "Audit campaign source exceeds maximum count" }
        }
        val manifest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return rows to manifest
    }

    /** A second collection in fresh snapshots detects additions, removals, or payload edits during export. */
    fun verifyUnchanged(first: List<AgentAuditEvidenceIdentity>, second: List<AgentAuditEvidenceIdentity>) {
        check(first == second) { "Audit inventory changed during collection" }
    }

    /** The database name is retained only in memory and never included in an error or manifest. */
    private fun serverDatabase(connection: Connection): String = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT current_database(), current_setting('transaction_read_only'), " +
                "current_setting('transaction_isolation')",
        ).use { rows ->
            check(rows.next()) { "Audit inventory could not verify server transaction" }
            check(rows.getString(2) == "on" && rows.getString(3) == "repeatable read") {
                "Audit inventory server transaction is not read-only repeatable-read"
            }
            rows.getString(1)?.takeIf { it.isNotBlank() }
                ?: error("Audit inventory database identity is unavailable")
        }
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
    private val SHA256 = Regex("[0-9a-f]{64}")
}
