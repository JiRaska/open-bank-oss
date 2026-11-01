// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "balance_low_alert_preferences")
class LowBalanceAlertPreferenceEntity : PanacheEntity() {
    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "currency", nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true

    @Column(name = "threshold", nullable = false, precision = 23, scale = 4)
    lateinit var threshold: BigDecimal

    @Column(name = "rearm_margin", nullable = false, precision = 23, scale = 4)
    lateinit var rearmMargin: BigDecimal

    @Column(name = "armed", nullable = false)
    var armed: Boolean = false

    @Column(name = "generation", nullable = false)
    var generation: Long = 0

    @Column(name = "last_alert_at")
    var lastAlertAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
