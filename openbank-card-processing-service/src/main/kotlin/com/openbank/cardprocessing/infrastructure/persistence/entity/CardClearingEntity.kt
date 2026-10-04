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
 * One applied clearing presentment (V4). Insert-only: a clearing is a fact, never rewritten, so
 * `persist` (INSERT) is the correct and only write — unlike the authorisation, which is updated.
 * Every column name is explicit for the same reason as [CardAuthorizationEntity].
 */
@Entity
@Table(name = "card_clearings")
class CardClearingEntity {
    @Id
    @Column(name = "id")
    lateinit var id: UUID

    @Column(name = "authorization_id")
    lateinit var authorizationId: UUID

    @Column(name = "idempotency_key")
    lateinit var idempotencyKey: String

    @Column(name = "request_fingerprint")
    lateinit var requestFingerprint: String

    @Column(name = "amount_minor_units")
    var amountMinorUnits: Long = 0

    @Column(name = "currency_code")
    lateinit var currencyCode: String

    @Column(name = "applied_at")
    lateinit var appliedAt: Instant
}
