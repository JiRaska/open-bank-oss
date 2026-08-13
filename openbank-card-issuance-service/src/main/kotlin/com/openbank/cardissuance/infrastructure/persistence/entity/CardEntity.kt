// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "cards")
class CardEntity {
    @Id
    @Column(name = "id")
    lateinit var id: UUID

    @Column(name = "idempotency_key", unique = true, nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "product_code", nullable = false)
    lateinit var productCode: String

    @Column(name = "card_type", nullable = false)
    lateinit var cardType: String

    @Column(name = "network", nullable = false)
    lateinit var network: String

    @Column(name = "masked_pan", nullable = false)
    lateinit var maskedPan: String

    @Column(name = "cardholder_name", nullable = false)
    lateinit var cardholderName: String

    @Column(name = "embossed_name", nullable = false)
    lateinit var embossedName: String

    @Column(name = "expiry_date", nullable = false)
    lateinit var expiryDate: LocalDate

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "daily_limit_minor_units", nullable = false)
    var dailyLimitMinorUnits: Long = 0

    @Column(name = "monthly_limit_minor_units", nullable = false)
    var monthlyLimitMinorUnits: Long = 0

    @Column(name = "contactless_enabled", nullable = false)
    var contactlessEnabled: Boolean = true

    @Column(name = "online_enabled", nullable = false)
    var onlineEnabled: Boolean = true

    @Column(name = "atm_enabled", nullable = false)
    var atmEnabled: Boolean = true

    @Column(name = "abroad_enabled", nullable = false)
    var abroadEnabled: Boolean = true

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "delivery_address")
    var deliveryAddress: String? = null

    @Column(name = "activated_at")
    var activatedAt: Instant? = null

    @Column(name = "blocked_at")
    var blockedAt: Instant? = null

    @Column(name = "blocked_reason")
    var blockedReason: String? = null

    /** SINGLE_USE only: when the card stops being usable even if never presented. */
    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    /** Why the card reached a terminal status; null while alive. */
    @Column(name = "closed_reason")
    var closedReason: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    // Synthetic PAN vault: base64(IV ‖ AES-256-GCM ciphertext ‖ tag). Nullable — cards issued
    // before V6 have no stored credential at all.
    @Column(name = "pan_encrypted")
    var panEncrypted: String? = null

    @Column(name = "cvv_encrypted")
    var cvvEncrypted: String? = null

    // ADR-0249 D1: the grant that authorised issuing this card to a delegate. NULL for an ordinary
    // card. Written once at issue and never updated — see Card.delegationGrantId.
    @Column(name = "delegation_grant_id")
    var delegationGrantId: UUID? = null
}
