// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.legalprocess

import com.openbank.legalprocess.isds.IsdsInbox
import com.openbank.legalprocess.isds.MAX_SIGNED_EVIDENCE_BYTES
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

/** Blocking port for the intake worker. Commit the pending review row before acknowledging delivery. */
class PostgresLegalDeliveryInbox(private val dataSource: DataSource) :
    LegalDeliveryInbox,
    IsdsInbox {
    override fun record(delivery: ObservedLegalDelivery): LegalDeliveryOutcome {
        require(!delivery.sourceNamespace.startsWith("isds:")) { "Reserved ISDS namespace" }
        return transact { connection -> record(connection, delivery) }
    }

    override fun record(
        mailboxNamespace: String,
        messageId: String,
        message: ByteArray,
        delivery: ByteArray,
    ): LegalDeliveryOutcome {
        require(message.size in 1..MAX_SIGNED_EVIDENCE_BYTES && delivery.size in 1..MAX_SIGNED_EVIDENCE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(message.size).array())
        digest.update(message)
        val observed = ObservedLegalDelivery(
            sourceNamespace = "isds:$mailboxNamespace",
            deliveryId = messageId,
            route = IntakeRoute.CZ,
            evidenceId = UUID.randomUUID(),
            contentSha256 = HexFormat.of().formatHex(digest.digest(delivery)),
        )
        return transact { connection ->
            val outcome = record(connection, observed, isIsds = true)
            if (outcome is LegalDeliveryOutcome.Received) {
                connection.prepareStatement(INSERT_ISDS).use {
                    it.setObject(RECEIPT_PARAM, observed.evidenceId)
                    it.setBytes(SOURCE_PARAM, message)
                    it.setBytes(DELIVERY_PARAM, delivery)
                    check(it.executeUpdate() == 1)
                }
            }
            outcome
        }
    }

    private fun record(
        connection: Connection,
        delivery: ObservedLegalDelivery,
        isIsds: Boolean = false,
    ): LegalDeliveryOutcome {
        val receipt = UUID.randomUUID()
        return if (insert(connection, delivery, receipt)) {
            LegalDeliveryOutcome.Received(receipt)
        } else {
            classifyReplay(connection, delivery, isIsds)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> transact(action: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        try {
            val outcome = action(connection)
            connection.commit()
            outcome
        } catch (failure: Exception) {
            // The same key is safe to retry even when the commit outcome is uncertain.
            try {
                connection.rollback()
            } catch (rollbackFailure: SQLException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun insert(connection: Connection, delivery: ObservedLegalDelivery, receipt: UUID): Boolean =
        connection.prepareStatement(INSERT).use {
            it.setObject(RECEIPT_PARAM, receipt)
            it.setString(SOURCE_PARAM, delivery.sourceNamespace)
            it.setString(DELIVERY_PARAM, delivery.deliveryId)
            it.setString(ROUTE_PARAM, delivery.route.name)
            it.setObject(EVIDENCE_PARAM, delivery.evidenceId)
            it.setString(DIGEST_PARAM, delivery.contentSha256)
            it.executeUpdate() == 1
        }

    private fun classifyReplay(
        connection: Connection,
        delivery: ObservedLegalDelivery,
        isIsds: Boolean,
    ): LegalDeliveryOutcome = connection.prepareStatement(FIND).use { statement ->
        statement.setString(1, delivery.sourceNamespace)
        statement.setString(2, delivery.deliveryId)
        // At READ COMMITTED a new statement sees the winner after ON CONFLICT waited on it.
        statement.executeQuery().use { row ->
            check(row.next()) { "Existing legal receipt disappeared during replay" }
            val sameRoute = row.getString("route_country") == delivery.route.name && row.getBoolean("is_isds") == isIsds
            val sameEvidence = isIsds || row.getObject("evidence_id", UUID::class.java) == delivery.evidenceId
            if (sameRoute && sameEvidence && row.getString("content_sha256") == delivery.contentSha256) {
                LegalDeliveryOutcome.Duplicate(row.getObject("receipt_id", UUID::class.java))
            } else {
                LegalDeliveryOutcome.Conflict
            }
        }
    }

    private companion object {
        const val RECEIPT_PARAM = 1
        const val SOURCE_PARAM = 2
        const val DELIVERY_PARAM = 3
        const val ROUTE_PARAM = 4
        const val EVIDENCE_PARAM = 5
        const val DIGEST_PARAM = 6

        const val INSERT = """
            INSERT INTO legal_delivery_inbox
              (receipt_id, source_namespace, delivery_id, route_country, evidence_id, content_sha256)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_namespace, delivery_id) DO NOTHING
        """

        const val FIND = """
            SELECT i.receipt_id, i.route_country, i.content_sha256, i.evidence_id,
                   (e.evidence_id IS NOT NULL) AS is_isds
            FROM legal_delivery_inbox i
            LEFT JOIN legal_isds_evidence e ON e.evidence_id = i.evidence_id
            WHERE i.source_namespace = ? AND i.delivery_id = ?
        """

        const val INSERT_ISDS = """
            INSERT INTO legal_isds_evidence (evidence_id, signed_message, signed_delivery_info)
            VALUES (?, ?, ?)
        """
    }
}
