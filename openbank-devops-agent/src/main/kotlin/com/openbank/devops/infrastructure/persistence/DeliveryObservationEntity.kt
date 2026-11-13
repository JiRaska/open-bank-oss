// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "delivery_observations")
class DeliveryObservationEntity {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "repository", nullable = false)
    lateinit var repository: String

    @Column(name = "slot_start", nullable = false)
    lateinit var slotStart: Instant

    @Column(name = "observed_at", nullable = false)
    lateinit var observedAt: Instant

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant

    @Column(name = "collector_version", nullable = false)
    lateinit var collectorVersion: String

    @Column(name = "schema_version", nullable = false)
    var schemaVersion: Int = 1

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "jsonb", nullable = false)
    lateinit var snapshotJson: String
}
