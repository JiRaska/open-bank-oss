// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.legalprocess.review

import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

internal enum class ReviewTeam { ENFORCEMENT_CZ, LEGAL_REVIEW_CZ }

internal enum class EvidenceFile(val column: String, val audit: String) {
    MESSAGE("signed_message", "DOWNLOAD_MESSAGE"),
    DELIVERY("signed_delivery_info", "DOWNLOAD_DELIVERY"),
}

internal data class InboxReceipt(
    val receiptId: UUID,
    val deliveryId: String,
    val recordedAt: OffsetDateTime,
    val revision: Int,
    val team: String?,
    val extractionStatus: String?,
)

internal data class InboxDetail(
    val receipt: InboxReceipt,
    val draft: EnvelopeDraft?,
    val canReadEvidence: Boolean,
    val canAssign: Boolean,
)

internal data class InboxOperator(val subject: String, val mailboxes: List<String>)

internal class ReceiptMissing : RuntimeException()
internal class RevisionConflict : RuntimeException()

@ApplicationScoped
internal class PostgresInboxReview(private val dataSource: DataSource) {
    fun list(operator: InboxOperator, offset: Int): List<InboxReceipt> = dataSource.connection.use { db ->
        db.prepareStatement("$SELECT ORDER BY i.recorded_at DESC, i.receipt_id DESC LIMIT 51 OFFSET ?").use { sql ->
            sql.setArray(1, db.createArrayOf("text", operator.mailboxes.toTypedArray()))
            sql.setInt(2, offset)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.receipt()) } }
        }
    }

    fun detail(id: UUID, operator: InboxOperator, canRead: Boolean, canAssign: Boolean): InboxDetail =
        transaction { db ->
            find(db, id, operator) { row ->
                val receipt = row.receipt()
                val draft = if (canRead && receipt.extractionStatus != null) {
                    EnvelopeDraft(
                        ExtractionStatus.valueOf(receipt.extractionStatus),
                        row.getString("subject"),
                        row.getString("sender_reference"),
                        row.getObject("attachment_count", Int::class.javaObjectType),
                    )
                } else {
                    null
                }
                if (draft != null) audit(db, id, operator, "READ_DRAFT", receipt.revision)
                InboxDetail(receipt, draft, canRead, canAssign)
            }
        }

    fun original(id: UUID, operator: InboxOperator, file: EvidenceFile): ByteArray = transaction { db ->
        val receipt = find(db, id, operator, map = ResultSet::receipt)
        val bytes = evidence(db, id, file)
        audit(db, id, operator, file.audit, receipt.revision)
        bytes
    }

    @Suppress("MagicNumber") // JDBC parameter positions.
    fun extract(id: UUID, operator: InboxOperator) {
        transaction { db ->
            val receipt = find(db, id, operator, lock = true, map = ResultSet::receipt)
            // Extraction from this immutable original and parser version is deterministic.
            if (receipt.extractionStatus != null) return@transaction
            val draft = IsdsEnvelopeExtraction.read(evidence(db, id, EvidenceFile.MESSAGE), receipt.deliveryId)
            db.prepareStatement(EXTRACT).use { sql ->
                sql.setObject(1, id)
                sql.setString(2, draft.status.name)
                sql.setString(3, draft.subject)
                sql.setString(4, draft.senderReference)
                sql.setObject(5, draft.attachmentCount)
                check(sql.executeUpdate() == 1)
            }
            audit(db, id, operator, "EXTRACT", receipt.revision)
        }
    }

    @Suppress("MagicNumber") // JDBC parameter positions.
    fun assign(id: UUID, operator: InboxOperator, team: ReviewTeam, expected: Int) {
        transaction { db ->
            val receipt = find(db, id, operator, lock = true, map = ResultSet::receipt)
            if (receipt.revision != expected) throw RevisionConflict()
            if (receipt.team == team.name) return@transaction
            val revision = Math.addExact(expected, 1)
            db.prepareStatement(ASSIGN).use { sql ->
                sql.setObject(1, id)
                sql.setString(2, team.name)
                sql.setInt(3, revision)
                check(sql.executeUpdate() == 1)
            }
            audit(db, id, operator, "ASSIGN", revision, receipt.team, team.name)
        }
    }

    private fun <T> find(
        db: Connection,
        id: UUID,
        operator: InboxOperator,
        lock: Boolean = false,
        map: (ResultSet) -> T,
    ): T {
        if (lock) {
            // Read review state in a fresh READ COMMITTED statement after acquiring the receipt lock.
            findRow(db, id, operator, "$SELECT AND i.receipt_id = ? FOR UPDATE OF i") { Unit }
        }
        return findRow(db, id, operator, "$SELECT AND i.receipt_id = ?", map)
    }

    private fun <T> findRow(
        db: Connection,
        id: UUID,
        operator: InboxOperator,
        query: String,
        map: (ResultSet) -> T,
    ): T = db.prepareStatement(query).use { sql ->
        sql.setArray(1, db.createArrayOf("text", operator.mailboxes.toTypedArray()))
        sql.setObject(2, id)
        sql.executeQuery().use { row ->
            if (!row.next()) throw ReceiptMissing()
            map(row)
        }
    }

    private fun evidence(db: Connection, id: UUID, file: EvidenceFile): ByteArray = db.prepareStatement(
        "SELECT e.${file.column} FROM legal_delivery_inbox i JOIN legal_isds_evidence e USING (evidence_id) " +
            "WHERE i.receipt_id = ?",
    ).use { sql ->
        sql.setObject(1, id)
        sql.executeQuery().use { row ->
            check(row.next())
            row.getBytes(1)
        }
    }

    @Suppress("LongParameterList", "MagicNumber")
    private fun audit(
        db: Connection,
        id: UUID,
        operator: InboxOperator,
        action: String,
        revision: Int,
        previousTeam: String? = null,
        nextTeam: String? = null,
    ) {
        db.prepareStatement(AUDIT).use { sql ->
            sql.setObject(1, id)
            sql.setString(2, operator.subject)
            sql.setString(3, action)
            sql.setInt(4, revision)
            sql.setString(5, previousTeam)
            sql.setString(6, nextTeam)
            check(sql.executeUpdate() == 1)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> transaction(run: (Connection) -> T): T = dataSource.connection.use { db ->
        db.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        db.autoCommit = false
        try {
            val result = run(db)
            db.commit()
            result
        } catch (failure: Exception) {
            try {
                db.rollback()
            } catch (rollbackFailure: SQLException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private companion object {
        const val SELECT = """
            SELECT i.receipt_id, i.delivery_id, i.recorded_at, COALESCE(r.revision, 0) AS revision,
                   r.team, r.extraction_status, r.subject, r.sender_reference, r.attachment_count
            FROM legal_delivery_inbox i
            JOIN legal_isds_evidence e USING (evidence_id)
            LEFT JOIN legal_inbox_review r USING (receipt_id)
            WHERE i.route_country = 'CZ' AND i.source_namespace = ANY(?)
        """
        const val EXTRACT = """
            INSERT INTO legal_inbox_review (receipt_id, extraction_status, subject, sender_reference, attachment_count)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (receipt_id) DO UPDATE SET extraction_status = EXCLUDED.extraction_status,
                subject = EXCLUDED.subject, sender_reference = EXCLUDED.sender_reference,
                attachment_count = EXCLUDED.attachment_count
        """
        const val ASSIGN = """
            INSERT INTO legal_inbox_review (receipt_id, team, revision) VALUES (?, ?, ?)
            ON CONFLICT (receipt_id) DO UPDATE SET team = EXCLUDED.team, revision = EXCLUDED.revision
        """
        const val AUDIT = """
            INSERT INTO legal_inbox_audit (receipt_id, actor_subject, action, review_revision, previous_team, next_team)
            VALUES (?, ?, ?, ?, ?, ?)
        """
    }
}

private fun ResultSet.receipt() = InboxReceipt(
    getObject("receipt_id", UUID::class.java),
    getString("delivery_id"),
    getObject("recorded_at", OffsetDateTime::class.java),
    getInt("revision"),
    getString("team"),
    getString("extraction_status"),
)
