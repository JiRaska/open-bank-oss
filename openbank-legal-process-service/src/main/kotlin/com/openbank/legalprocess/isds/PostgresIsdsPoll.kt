// SPDX-License-Identifier: Apache-2.0

package com.openbank.legalprocess.isds

import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

internal data class IsdsPollRun(
    val mailbox: String,
    val windowFrom: OffsetDateTime,
    val windowThrough: OffsetDateTime,
    val startedAt: OffsetDateTime,
    val discoveredAt: OffsetDateTime?,
    val status: String,
    val discoveredCount: Int,
    val importedCount: Int,
    val pendingCount: Long?,
)

@ApplicationScoped
internal class PostgresIsdsPoll(private val source: DataSource) {
    fun <T> exclusive(mailbox: String, run: LockedMailbox.() -> T): T? = source.connection.use { db ->
        val acquired = db.query("SELECT pg_try_advisory_lock(hashtextextended(?, 381))", "isds-poll:$mailbox") {
            next() && getBoolean(1)
        }
        if (!acquired) return null
        try {
            LockedMailbox(db, mailbox).apply { recover() }.run()
        } finally {
            db.query("SELECT pg_advisory_unlock(hashtextextended(?, 381))", "isds-poll:$mailbox") {
                check(next() && getBoolean(1))
            }
        }
    }

    fun recent(mailboxes: List<String>): List<IsdsPollRun> = source.connection.use { db ->
        db.query(
            """
            SELECT mailbox, window_from, window_through, started_at, discovered_at, status,
                   discovered_count, imported_count, pending_count
            FROM legal_isds_poll_run WHERE mailbox = ANY(?)
            ORDER BY started_at DESC, id DESC LIMIT 50
            """.trimIndent(),
            db.createArrayOf("text", mailboxes.toTypedArray()),
        ) {
            buildList {
                while (next()) {
                    add(
                        IsdsPollRun(
                            getString("mailbox"),
                            getObject("window_from", OffsetDateTime::class.java),
                            getObject("window_through", OffsetDateTime::class.java),
                            getObject("started_at", OffsetDateTime::class.java),
                            getObject("discovered_at", OffsetDateTime::class.java),
                            getString("status"),
                            getInt("discovered_count"),
                            getInt("imported_count"),
                            getObject("pending_count", Long::class.javaObjectType),
                        ),
                    )
                }
            }
        }
    }

    internal class LockedMailbox(private val db: Connection, private val mailbox: String) {
        fun recover() {
            db.update(
                """
                UPDATE legal_isds_poll_run SET status = 'INTERRUPTED', finished_at = clock_timestamp()
                WHERE mailbox = ? AND status = 'RUNNING'
                """.trimIndent(),
                mailbox,
            )
        }

        fun cursor(): Instant? = db.query(
            "SELECT max(window_through) FROM legal_isds_poll_run WHERE mailbox = ? AND discovered_at IS NOT NULL",
            mailbox,
        ) {
            check(next())
            getObject(1, OffsetDateTime::class.java)?.toInstant()
        }

        fun start(from: Instant, through: Instant): UUID {
            val run = UUID.randomUUID()
            db.update(
                "INSERT INTO legal_isds_poll_run (id, mailbox, window_from, window_through, status)" +
                    " VALUES (?, ?, ?, ?, 'RUNNING')",
                run,
                mailbox,
                from.atOffset(ZoneOffset.UTC),
                through.atOffset(ZoneOffset.UTC),
            )
            return run
        }

        fun recordDiscovery(run: UUID, ids: Set<String>) {
            check(
                db.update(
                    """
                    WITH observed AS (
                        INSERT INTO legal_isds_pending (mailbox, delivery_id)
                        SELECT ?, d.id FROM unnest(?::text[]) AS d(id)
                        WHERE NOT EXISTS (
                            SELECT 1 FROM legal_delivery_inbox i JOIN legal_isds_evidence e USING (evidence_id)
                            WHERE i.source_namespace = ? AND i.delivery_id = d.id
                        )
                        ON CONFLICT DO NOTHING
                    )
                    UPDATE legal_isds_poll_run SET discovered_at = clock_timestamp(), discovered_count = ?
                    WHERE id = ?
                    """.trimIndent(),
                    mailbox,
                    db.createArrayOf("text", ids.toTypedArray()),
                    "isds:$mailbox",
                    ids.size,
                    run,
                ) == 1,
            )
        }

        fun pending(): List<String> = db.query(
            """
            SELECT delivery_id FROM legal_isds_pending
            WHERE mailbox = ? AND (attempted_at IS NULL OR attempted_at <= clock_timestamp() - interval '5 minutes')
            ORDER BY attempted_at NULLS FIRST, first_seen, delivery_id LIMIT 25
            """.trimIndent(),
            mailbox,
        ) { buildList { while (next()) add(getString(1)) } }

        fun alreadyStored(id: String): Boolean = db.query(
            """
            SELECT EXISTS (
                SELECT 1 FROM legal_delivery_inbox i JOIN legal_isds_evidence e USING (evidence_id)
                WHERE i.source_namespace = ? AND i.delivery_id = ?
            )
            """.trimIndent(),
            "isds:$mailbox",
            id,
        ) { next() && getBoolean(1) }

        fun attempted(id: String) {
            db.update(
                "UPDATE legal_isds_pending SET attempted_at = clock_timestamp() WHERE mailbox = ? AND delivery_id = ?",
                mailbox,
                id,
            )
        }

        fun complete(id: String, run: UUID, imported: Boolean) {
            db.update(
                """
                WITH completed AS (
                    DELETE FROM legal_isds_pending WHERE mailbox = ? AND delivery_id = ? RETURNING delivery_id
                )
                UPDATE legal_isds_poll_run SET imported_count = imported_count + (SELECT count(*) FROM completed)
                WHERE id = ? AND ?
                """.trimIndent(),
                mailbox,
                id,
                run,
                imported,
            )
        }

        fun finish(run: UUID, status: String? = null) {
            db.update(
                """
                UPDATE legal_isds_poll_run SET finished_at = clock_timestamp(), pending_count = backlog.size,
                    status = coalesce(?, CASE WHEN backlog.size = 0 THEN 'COMPLETE' ELSE 'INCOMPLETE' END)
                FROM (SELECT count(*) AS size FROM legal_isds_pending WHERE mailbox = ?) AS backlog
                WHERE id = ?
                """.trimIndent(),
                status,
                mailbox,
                run,
            )
        }
    }
}

private fun Connection.update(query: String, vararg parameters: Any?): Int = prepareStatement(query).use { sql ->
    parameters.forEachIndexed { index, parameter -> sql.setObject(index + 1, parameter) }
    sql.executeUpdate()
}

private fun <T> Connection.query(query: String, vararg parameters: Any?, read: ResultSet.() -> T): T =
    prepareStatement(query).use { sql ->
        parameters.forEachIndexed { index, parameter -> sql.setObject(index + 1, parameter) }
        sql.executeQuery().use(read)
    }
