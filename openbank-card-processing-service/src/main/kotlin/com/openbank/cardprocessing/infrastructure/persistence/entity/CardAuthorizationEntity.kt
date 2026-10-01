// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Every column name is spelled out, including the single-word ones.
 *
 * Hibernate's implicit name is the property name verbatim and Postgres folds an unquoted identifier
 * to lower case, so `authorizedAt` would resolve to `authorizedat` while the migration wrote
 * `authorized_at`. This service does not set `physical-naming-strategy`, so the implicit name is
 * wrong for every multi-word property and right for every single-word one — which reads as
 * internally consistent and is how consent-service shipped an endpoint that answered 500 on every
 * call from the day it shipped (`check-entity-column-names.py`, CLAUDE.md).
 */
@Entity
@Table(name = "card_authorizations")
class CardAuthorizationEntity {
    @Id
    @Column(name = "id")
    lateinit var id: UUID

    @Column(name = "card_id")
    lateinit var cardId: UUID

    @Column(name = "account_id")
    lateinit var accountId: UUID

    @Column(name = "party_id")
    lateinit var partyId: UUID

    @Column(name = "amount_minor_units")
    var amountMinorUnits: Long = 0

    @Column(name = "currency_code")
    lateinit var currencyCode: String

    @Column(name = "channel")
    lateinit var channel: String

    @Column(name = "mcc")
    var mcc: String? = null

    @Column(name = "merchant_name")
    var merchantName: String? = null

    @Column(name = "merchant_country")
    var merchantCountry: String? = null

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "category")
    lateinit var category: String

    @Column(name = "decline_reason")
    var declineReason: String? = null

    @Column(name = "cleared_amount_minor_units")
    var clearedAmountMinorUnits: Long = 0

    @Column(name = "network_reference")
    var networkReference: String? = null

    @Column(name = "idempotency_key")
    lateinit var idempotencyKey: String

    @Column(name = "authorized_at")
    lateinit var authorizedAt: Instant

    @Column(name = "expires_at")
    lateinit var expiresAt: Instant

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant
}
