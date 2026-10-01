// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/**
 * `claimed_at` is per-service, not on the shared [PanacheOutboxEntity]: a column on the shared
 * entity would need every outbox-bearing service migrated in lockstep. Stamped and read back by the
 * atomic claim query, which is what stops two pods publishing one row during a canary window.
 */
@Entity
@Table(name = "card_outbox")
class CardProcessingOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
