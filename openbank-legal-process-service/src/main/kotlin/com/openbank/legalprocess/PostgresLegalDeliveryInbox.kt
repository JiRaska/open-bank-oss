// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.legalprocess

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Blocking port for the intake worker. Commit the pending review row before acknowledging delivery. */
class PostgresLegalDeliveryInbox(private val dataSource: DataSource) : LegalDeliveryInbox {
    @Suppress("TooGenericExceptionCaught")
    override fun record(delivery: ObservedLegalDelivery): LegalDeliveryOutcome =
        dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                val receipt = UUID.randomUUID()
                val outcome = if (insert(connection, delivery, receipt)) {
                    LegalDeliveryOutcome.Received(receipt)
                } else {
                    classifyReplay(connection, delivery)
                }
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

    private fun classifyReplay(connection: Connection, delivery: ObservedLegalDelivery): LegalDeliveryOutcome =
        connection.prepareStatement(FIND).use { statement ->
            statement.setString(1, delivery.sourceNamespace)
            statement.setString(2, delivery.deliveryId)
            // At READ COMMITTED a new statement sees the winner after ON CONFLICT waited on it.
            statement.executeQuery().use { row ->
                check(row.next()) { "Existing legal receipt disappeared during replay" }
                if (row.getString("route_country") == delivery.route.name &&
                    row.getString("content_sha256") == delivery.contentSha256 &&
                    row.getObject("evidence_id", UUID::class.java) == delivery.evidenceId
                ) {
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
            SELECT receipt_id, route_country, content_sha256, evidence_id FROM legal_delivery_inbox
            WHERE source_namespace = ? AND delivery_id = ?
        """
    }
}
