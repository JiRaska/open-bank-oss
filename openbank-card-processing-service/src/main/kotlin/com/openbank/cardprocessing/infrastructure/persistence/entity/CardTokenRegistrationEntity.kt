// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Every column name is spelled out — see [CardAuthorizationEntity] for what an implicit name costs.
 *
 * Nothing here is a card credential: [tokenReference] is the network's opaque handle and [last4] is
 * the token's own display digits, which the network derives and this service never computes.
 */
@Entity
@Table(name = "card_network_tokens")
class CardTokenRegistrationEntity {
    @Id
    @Column(name = "id")
    lateinit var id: UUID

    @Column(name = "card_id")
    lateinit var cardId: UUID

    @Column(name = "token_reference")
    lateinit var tokenReference: String

    @Column(name = "requestor_id")
    lateinit var requestorId: String

    @Column(name = "requestor_label")
    lateinit var requestorLabel: String

    @Column(name = "last4")
    lateinit var last4: String

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "scheme")
    lateinit var scheme: String

    @Column(name = "expiry")
    var expiry: LocalDate? = null

    @Column(name = "idempotency_key")
    lateinit var idempotencyKey: String

    @Column(name = "provisioned_at")
    lateinit var provisionedAt: Instant

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant
}
