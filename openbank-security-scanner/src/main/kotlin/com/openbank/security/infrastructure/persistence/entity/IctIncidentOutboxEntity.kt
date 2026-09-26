// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** Durable hand-off for one strictly ordered ICT incident transition. */
@Entity
@Table(
    name = "ict_incident_outbox",
    uniqueConstraints = [UniqueConstraint(columnNames = ["aggregate_id", "aggregate_revision"])],
)
class IctIncidentOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "aggregate_revision", nullable = false)
    var aggregateRevision: Long = 0

    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
