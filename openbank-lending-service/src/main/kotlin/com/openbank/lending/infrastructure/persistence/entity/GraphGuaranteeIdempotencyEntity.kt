// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.lending.application.port.out.GraphGuaranteeIdempotencyConflict
import com.openbank.lending.application.port.out.GraphGuaranteeReceipt
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "lending_graph_guarantee_idempotency")
class GraphGuaranteeIdempotencyEntity {
    @Id
    @Column(name = "receipt_id")
    var receiptId: UUID = UUID.randomUUID()

    @Column(name = "operation", length = 16)
    lateinit var operation: String

    @Column(name = "idempotency_key", length = 128)
    lateinit var idempotencyKey: String

    @Column(name = "fingerprint", length = 64)
    lateinit var fingerprint: String

    @Column(name = "guarantee_id")
    lateinit var guaranteeId: UUID

    @Column(name = "revision")
    var revision: Long = 1

    @Column(name = "response_status", length = 16)
    lateinit var responseStatus: String

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    fun receiptFor(expectedFingerprint: String): GraphGuaranteeReceipt {
        if (fingerprint != expectedFingerprint) throw GraphGuaranteeIdempotencyConflict()
        return GraphGuaranteeReceipt(guaranteeId, revision, GraphGuaranteeStatus.valueOf(responseStatus))
    }

    companion object {
        fun completed(
            operation: String,
            key: String,
            fingerprint: String,
            receipt: GraphGuaranteeReceipt,
            at: Instant,
        ) = GraphGuaranteeIdempotencyEntity().also {
            it.operation = operation
            it.idempotencyKey = key
            it.fingerprint = fingerprint
            it.guaranteeId = receipt.guaranteeId
            it.revision = receipt.revision
            it.responseStatus = receipt.status.name
            it.createdAt = at
        }
    }
}
