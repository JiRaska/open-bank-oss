// SPDX-License-Identifier: Apache-2.0
package com.openbank.sepa.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Source-local outcome evidence. Its presence alone never attributes a payment to an incident. */
@Entity
@Table(name = "sepa_payment_workflow_observations")
class SepaWorkflowObservationEntity : PanacheEntityBase {
    @Id
    @Column(name = "event_id", nullable = false)
    lateinit var eventId: UUID

    @Column(name = "payment_id", nullable = false)
    lateinit var paymentId: UUID

    @Column(name = "payment_revision", nullable = false)
    var paymentRevision: Long = 0

    @Column(name = "event_type", nullable = false)
    lateinit var eventType: String

    @Column(name = "payment_status", nullable = false)
    lateinit var paymentStatus: String

    @Column(name = "content_digest", nullable = false)
    lateinit var contentDigest: String

    @Column(name = "observed_at", nullable = false)
    lateinit var observedAt: Instant

    @Column(name = "synthetic", nullable = false)
    var synthetic: Boolean = false

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant
}
