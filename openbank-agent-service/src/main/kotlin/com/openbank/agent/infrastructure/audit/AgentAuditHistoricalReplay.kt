// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** A separate cursor over already published source rows. It never changes outbox delivery state. */
@ApplicationScoped
// MagicNumber: JDBC ResultSet/PreparedStatement positions are the fixed SQL projection order.
@Suppress("MagicNumber")
class AgentAuditReplayStore(private val dataSource: DataSource) {
    data class Cursor(val createdAt: Instant, val eventId: UUID, val payloadSha256: String)

    data class Campaign(
        val id: UUID,
        val from: Instant,
        val until: Instant,
        val maxEvents: Int,
        val expectedCount: Int,
        val sourceManifestSha256: String,
        val cursor: Cursor?,
        val acknowledgedCount: Int,
    )

    data class Row(val createdAt: Instant, val eventId: UUID, val payload: String)

    data class Inventory(val total: Int, val unpublished: Int)

    /** Session-scoped advisory lock prevents two replicas from exceeding the campaign rate cap. */
    // TooGenericExceptionCaught: any JDBC or pool exception after acquiring a connection must
    // close it so the session lock cannot remain attached to a pooled connection.
    @Suppress("TooGenericExceptionCaught")
    fun acquire(): Connection? {
        val connection = dataSource.connection
        try {
            connection.autoCommit = true
            val locked = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
                ps.setLong(1, REPLAY_LOCK_KEY)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (locked) return connection
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        connection.close()
        return null
    }

    fun release(connection: Connection) {
        try {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                ps.setLong(1, REPLAY_LOCK_KEY)
                ps.executeQuery().close()
            }
        } finally {
            connection.close()
        }
    }

    fun inventory(connection: Connection, from: Instant, until: Instant): Inventory = connection.prepareStatement(
        "SELECT count(*), count(*) FILTER (WHERE published_at IS NULL) " +
            "FROM agent_audit_outbox WHERE created_at >= ? AND created_at < ?",
    ).use { ps ->
        ps.setTimestamp(1, Timestamp.from(from))
        ps.setTimestamp(2, Timestamp.from(until))
        ps.executeQuery().use { rs ->
            check(rs.next()) { "Replay source inventory unavailable" }
            Inventory(rs.getInt(1), rs.getInt(2))
        }
    }

    fun campaign(
        connection: Connection,
        id: UUID,
        from: Instant,
        until: Instant,
        maxEvents: Int,
        expectedCount: Int,
        sourceManifestSha256: String,
    ): Campaign {
        connection.prepareStatement(
            "INSERT INTO agent_audit_replay_checkpoint " +
                "(campaign_id, from_at, until_at, max_events, expected_count, source_manifest_sha256) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (campaign_id) DO NOTHING",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setTimestamp(2, Timestamp.from(from))
            ps.setTimestamp(3, Timestamp.from(until))
            ps.setInt(4, maxEvents)
            ps.setInt(5, expectedCount)
            ps.setString(6, sourceManifestSha256)
            ps.executeUpdate()
        }
        return connection.prepareStatement(
            "SELECT from_at, until_at, max_events, expected_count, source_manifest_sha256, " +
                "cursor_created_at, cursor_event_id, cursor_payload_sha256, acknowledged_count " +
                "FROM agent_audit_replay_checkpoint WHERE campaign_id = ?",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "Replay campaign missing after insert" }
                val storedFrom = rs.getTimestamp(1).toInstant()
                val storedUntil = rs.getTimestamp(2).toInstant()
                val storedMaxEvents = rs.getInt(3)
                val storedCount = rs.getInt(4)
                val storedManifest = rs.getString(5)
                check(
                    storedFrom == from &&
                        storedUntil == until &&
                        storedMaxEvents == maxEvents &&
                        storedCount == expectedCount &&
                        storedManifest == sourceManifestSha256,
                ) {
                    "Replay campaign scope changed"
                }
                val cursorTime = rs.getTimestamp(6)?.toInstant()
                val cursor =
                    if (cursorTime == null) {
                        null
                    } else {
                        Cursor(cursorTime, rs.getObject(7, UUID::class.java), rs.getString(8))
                    }
                Campaign(
                    id,
                    storedFrom,
                    storedUntil,
                    storedMaxEvents,
                    storedCount,
                    storedManifest,
                    cursor,
                    rs.getInt(9),
                )
            }
        }
    }

    fun verifyCursor(connection: Connection, cursor: Cursor?) {
        if (cursor == null) return
        connection.prepareStatement(
            "SELECT payload FROM agent_audit_outbox WHERE event_id = ? AND created_at = ? AND published_at IS NOT NULL",
        ).use { ps ->
            ps.setObject(1, cursor.eventId)
            ps.setTimestamp(2, Timestamp.from(cursor.createdAt))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "Replay cursor source row disappeared or is unpublished" }
                check(sha256(rs.getString(1)) == cursor.payloadSha256) { "Replay cursor source payload changed" }
            }
        }
    }

    /** Cluster-wide cap: at most one 25-record batch per campaign per minute. */
    fun reserveBatch(connection: Connection, campaignId: UUID): Boolean = connection.prepareStatement(
        "UPDATE agent_audit_replay_checkpoint SET last_batch_at = NOW() " +
            "WHERE campaign_id = ? AND " +
            "(last_batch_at IS NULL OR last_batch_at <= NOW() - INTERVAL '60 seconds')",
    ).use { ps ->
        ps.setObject(1, campaignId)
        ps.executeUpdate() == 1
    }

    fun next(connection: Connection, campaign: Campaign, limit: Int): List<Row> = connection.prepareStatement(
        "SELECT created_at, event_id, payload FROM agent_audit_outbox " +
            "WHERE published_at IS NOT NULL AND created_at >= ? AND created_at < ? " +
            "AND (?::timestamptz IS NULL OR (created_at, event_id) > (?::timestamptz, ?::uuid)) " +
            "ORDER BY created_at, event_id LIMIT ?",
    ).use { ps ->
        ps.setTimestamp(1, Timestamp.from(campaign.from))
        ps.setTimestamp(2, Timestamp.from(campaign.until))
        ps.setTimestamp(3, campaign.cursor?.createdAt?.let(Timestamp::from))
        ps.setTimestamp(4, campaign.cursor?.createdAt?.let(Timestamp::from))
        ps.setObject(5, campaign.cursor?.eventId)
        ps.setInt(6, limit)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        Row(
                            rs.getTimestamp(1).toInstant(),
                            rs.getObject(2, UUID::class.java),
                            rs.getString(3),
                        ),
                    )
                }
            }
        }
    }

    /** Called only after the broker's send future has completed successfully. */
    fun checkpoint(connection: Connection, campaign: Campaign, previous: Cursor?, row: Row, maxEvents: Int) {
        connection.prepareStatement(
            "UPDATE agent_audit_replay_checkpoint " +
                "SET cursor_created_at = ?, cursor_event_id = ?, cursor_payload_sha256 = ?, " +
                "acknowledged_count = acknowledged_count + 1 " +
                "WHERE campaign_id = ? AND acknowledged_count < ? " +
                "AND cursor_created_at IS NOT DISTINCT FROM ? " +
                "AND cursor_event_id IS NOT DISTINCT FROM ?",
        ).use { ps ->
            ps.setTimestamp(1, Timestamp.from(row.createdAt))
            ps.setObject(2, row.eventId)
            ps.setString(3, sha256(row.payload))
            ps.setObject(4, campaign.id)
            ps.setInt(5, maxEvents)
            ps.setTimestamp(6, previous?.createdAt?.let(Timestamp::from))
            ps.setObject(7, previous?.eventId)
            check(ps.executeUpdate() == 1) { "Replay cursor changed or campaign limit reached" }
        }
    }

    companion object {
        private const val REPLAY_LOCK_KEY = 0x4f42414b41554449L

        fun sha256(payload: String): String = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Disabled until a bounded, reviewed campaign is deliberately configured. Dry-run is the default. */
@ApplicationScoped
// LongParameterList: the injected arguments keep each opt-in replay control explicit.
@Suppress("LongParameterList")
class AgentAuditHistoricalReplay @Inject constructor(
    private val store: AgentAuditReplayStore,
    private val mapper: ObjectMapper,
    @Channel("agent-audit-events-out") private val emitter: Instance<Emitter<Record<String, String>>>,
    @ConfigProperty(name = "agent.audit.replay.enabled", defaultValue = "false") private val enabled: Boolean,
    @ConfigProperty(name = "agent.audit.replay.execute", defaultValue = "false") private val execute: Boolean,
    @ConfigProperty(name = "agent.audit.kafka.enabled", defaultValue = "false") private val transportEnabled: Boolean,
    @ConfigProperty(name = "agent.audit.replay.campaign-id") private val campaignId: java.util.Optional<String>,
    @ConfigProperty(name = "agent.audit.replay.from") private val from: java.util.Optional<String>,
    @ConfigProperty(name = "agent.audit.replay.until") private val until: java.util.Optional<String>,
    @ConfigProperty(name = "agent.audit.replay.max-events", defaultValue = "25") private val maxEvents: Int,
    @ConfigProperty(name = "agent.audit.replay.expected-count") private val expectedCount: java.util.Optional<Int>,
    @ConfigProperty(name = "agent.audit.replay.expected-manifest-sha256")
    private val expectedManifestSha256: java.util.Optional<String>,
) {
    private val log = Logger.getLogger(AgentAuditHistoricalReplay::class.java)
    private val dryRunReported = AtomicBoolean(false)

    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun replay() {
        if (!enabled) return
        val id = UUID.fromString(campaignId.orElseThrow { IllegalStateException("Replay campaign id is required") })
        val lower = Instant.parse(from.orElseThrow { IllegalStateException("Replay start is required") })
        val upper = Instant.parse(until.orElseThrow { IllegalStateException("Replay end is required") })
        check(lower < upper && upper <= Instant.now()) { "Replay window must be bounded in the past" }
        check(
            lower == lower.truncatedTo(java.time.temporal.ChronoUnit.MICROS) &&
                upper == upper.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        ) { "Replay window exceeds database timestamp precision" }
        check(maxEvents in 1..MAX_CAMPAIGN_EVENTS) { "Replay max-events outside permitted range" }
        if (execute) check(transportEnabled) { "Replay transport disabled" }
        val connection = store.acquire() ?: return
        try {
            runWindow(connection, id, lower, upper)
        } finally {
            store.release(connection)
        }
    }

    private suspend fun runWindow(connection: Connection, id: UUID, lower: Instant, upper: Instant) {
        val inventory = store.inventory(connection, lower, upper)
        check(inventory.total in 1..maxEvents) { "Replay source count outside campaign limit" }
        check(inventory.unpublished == 0) { "Replay source has unpublished rows" }
        val manifest = validateEntireWindow(connection, id, lower, upper, inventory.total)
        // Dry-run is read-only, including the checkpoint table, and checks every source row.
        if (!execute) {
            if (dryRunReported.compareAndSet(false, true)) {
                log.infof(
                    "Replay dry-run count=%d manifest-sha256=%s",
                    inventory.total,
                    manifest,
                )
            }
            return
        }
        val approvedCount = expectedCount.orElseThrow { IllegalStateException("Replay expected count is required") }
        val approvedManifest = expectedManifestSha256.orElseThrow {
            IllegalStateException("Replay expected manifest SHA-256 is required")
        }
        check(approvedManifest.matches(Regex("[0-9a-f]{64}"))) { "Replay expected manifest SHA-256 is invalid" }
        check(approvedCount == inventory.total && approvedManifest == manifest) {
            "Replay source does not match approved count and manifest"
        }
        val campaign = store.campaign(
            connection,
            id,
            lower,
            upper,
            maxEvents,
            inventory.total,
            manifest,
        )
        sendBatch(connection, campaign)
    }

    private suspend fun sendBatch(connection: Connection, campaign: AgentAuditReplayStore.Campaign) {
        store.verifyCursor(connection, campaign.cursor)
        var cursor = campaign.cursor
        val allowance = minOf(
            BATCH_SIZE,
            maxEvents - campaign.acknowledgedCount,
            campaign.expectedCount - campaign.acknowledgedCount,
        )
        if (allowance <= 0) return
        if (!store.reserveBatch(connection, campaign.id)) return
        store.next(connection, campaign, allowance).forEach { row ->
            validate(row)
            Uni.createFrom().completionStage(
                emitter.get().send(Record.of(row.eventId.toString(), row.payload)),
            ).awaitSuspending()
            store.checkpoint(connection, campaign, cursor, row, maxEvents)
            cursor = AgentAuditReplayStore.Cursor(
                row.createdAt,
                row.eventId,
                AgentAuditReplayStore.sha256(row.payload),
            )
        }
    }

    private fun validateEntireWindow(
        connection: Connection,
        id: UUID,
        from: Instant,
        until: Instant,
        expectedCount: Int,
    ): String {
        var cursor: AgentAuditReplayStore.Cursor? = null
        var validated = 0
        val digest = MessageDigest.getInstance("SHA-256")
        while (validated < expectedCount) {
            val preview = AgentAuditReplayStore.Campaign(
                id,
                from,
                until,
                maxEvents,
                expectedCount,
                "",
                cursor,
                validated,
            )
            val rows = store.next(connection, preview, minOf(BATCH_SIZE, expectedCount - validated))
            check(rows.isNotEmpty()) { "Replay source inventory changed during dry-run" }
            rows.forEach { row ->
                validate(row)
                val manifestLine = "${row.createdAt}:${row.eventId}:${AgentAuditReplayStore.sha256(row.payload)}\n"
                digest.update(manifestLine.toByteArray())
                cursor = AgentAuditReplayStore.Cursor(
                    row.createdAt,
                    row.eventId,
                    AgentAuditReplayStore.sha256(row.payload),
                )
                validated++
            }
        }
        val after = store.inventory(connection, from, until)
        check(after.total == expectedCount && after.unpublished == 0) { "Replay source inventory changed" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
    }

    private fun validate(row: AgentAuditReplayStore.Row) {
        val node = mapper.readTree(row.payload)
        check(node.isObject) { "Replay payload is not an object" }
        check(node.path("eventId").asText() == row.eventId.toString()) { "Replay source event id mismatch" }
        check(node.path("sourceService").asText() == "agent-service") { "Replay source service mismatch" }
    }

    private companion object {
        const val BATCH_SIZE = 25
        const val MAX_CAMPAIGN_EVENTS = 5_000
        const val BYTE_MASK = 0xff
    }
}
