// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.engagement.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/**
 * `claimed_at` is consent-only — added straight on this entity, not the shared
 * [PanacheOutboxEntity] (mapped by every outbox-bearing service — a shared-entity migration
 * would need every service migrated in lockstep). Stamped by
 * `EngagementOutboxRepositoryImpl.claimProcessable`'s atomic claim query on DISPATCHING; read back
 * by the same query to decide if a DISPATCHING row is stale enough to reclaim.
 */
@Entity
@Table(name = "engagement_outbox")
class EngagementOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
