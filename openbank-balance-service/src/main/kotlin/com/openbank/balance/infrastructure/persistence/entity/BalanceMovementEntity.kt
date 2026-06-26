// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Idempotency marker for the direct credit/debit money-movement path (table `balance_movement`). One
 * row per applied movement, keyed by the (account, currency, referenceId, operation) the caller
 * supplied. Written in the SAME transaction as the balance mutation, so a redelivery that finds the
 * row present is skipped — the booked/available amounts move exactly once.
 */
@Entity
@Table(name = "balance_movement")
@IdClass(BalanceMovementId::class)
class BalanceMovementEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Id
    @Column(nullable = false, length = 3)
    lateinit var currency: String

    @Id
    @Column(name = "reference_id", nullable = false, length = 200)
    lateinit var referenceId: String

    @Id
    @Column(nullable = false, length = 8)
    lateinit var operation: String

    @Column(nullable = false, precision = 19, scale = 4)
    lateinit var delta: BigDecimal

    @Column(name = "applied_at", nullable = false)
    var appliedAt: OffsetDateTime = OffsetDateTime.MIN
}

/** Composite primary key for [BalanceMovementEntity]. */
data class BalanceMovementId(
    var accountId: UUID = UUID(0, 0),
    var currency: String = "",
    var referenceId: String = "",
    var operation: String = "",
) : Serializable
