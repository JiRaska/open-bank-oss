// SPDX-License-Identifier: Apache-2.0
package com.openbank.audit.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Receipt identity and digest are committed with the hash-chain entry, before Kafka acknowledgment. */
@Entity
@Table(name = "context_commitment_receipts")
class ContextCommitmentReceiptEntity : PanacheEntityBase() {
    @Id
    @Column(name = "event_id")
    lateinit var eventId: UUID

    @Column(name = "commitment")
    lateinit var commitment: String

    @Column(name = "record_hash")
    lateinit var recordHash: String

    @Column(name = "occurred_at")
    lateinit var occurredAt: Instant

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "attempt_count")
    var attemptCount: Int = 0

    @Column(name = "claimed_at")
    var claimedAt: Instant? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant
}
