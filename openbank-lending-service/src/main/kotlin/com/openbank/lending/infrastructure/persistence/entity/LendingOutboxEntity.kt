// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/**
 * `claimed_at` is lending-only — added straight on this entity, not on the shared
 * [PanacheOutboxEntity], because that base class is mapped by every outbox-bearing service and
 * adding a column there would break every other service's table until it also migrated (#1201
 * fleet rollout is deliberately incremental, one service's migration at a time). Stamped by
 * `LendingOutboxRepositoryImpl.claimProcessable`'s atomic claim query when a row moves to
 * [com.openbank.libs.persistence.outbox.OutboxStatus.DISPATCHING]; read back by the same query
 * to decide whether a DISPATCHING row is stale enough to reclaim.
 */
@Entity
@Table(name = "lending_outbox")
class LendingOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
