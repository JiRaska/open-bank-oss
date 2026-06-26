// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "balances")
class BalanceEntity : PanacheEntity() {
    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "booked_amount", nullable = false, precision = 19, scale = 4)
    lateinit var bookedAmount: BigDecimal

    @Column(name = "available_amount", nullable = false, precision = 19, scale = 4)
    lateinit var availableAmount: BigDecimal

    @Column(name = "reserved_amount", nullable = false, precision = 19, scale = 4)
    lateinit var reservedAmount: BigDecimal

    @Column(name = "pending_amount", nullable = false, precision = 19, scale = 4)
    lateinit var pendingAmount: BigDecimal

    @Column(name = "arranged_overdraft_limit", nullable = false, precision = 19, scale = 4)
    var arrangedOverdraftLimit: BigDecimal = BigDecimal.ZERO

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    @Version
    @Column(nullable = false)
    var version: Long = 0
}

@Entity
@Table(name = "balance_holds")
class BalanceHoldEntity : PanacheEntity() {
    @Column(name = "hold_id", nullable = false, unique = true)
    lateinit var holdId: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(nullable = false, precision = 19, scale = 4)
    lateinit var amount: BigDecimal

    @Column(nullable = false, length = 3)
    lateinit var currency: String

    @Column(nullable = false)
    lateinit var reason: String

    @Column(name = "reference_id", nullable = false)
    lateinit var referenceId: String

    @Column(name = "expires_at")
    var expiresAt: OffsetDateTime? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "released_at")
    var releasedAt: OffsetDateTime? = null
}
