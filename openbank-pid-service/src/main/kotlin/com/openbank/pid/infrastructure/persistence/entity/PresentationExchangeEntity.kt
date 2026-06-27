// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Panache entity for `eudi_presentation_exchange` (V9) — an in-flight OpenID4VP exchange (ADR-0094).
 * [transactionId] (the OpenID4VP `state`) is the natural primary key; [resultJson] holds the resolved
 * decision (PidClaims + the resolution verdict) once the wallet has posted, for the poll endpoint.
 */
@Entity
@Table(name = "eudi_presentation_exchange")
class PresentationExchangeEntity : PanacheEntityBase {
    @Id
    @Column(name = "transaction_id")
    lateinit var transactionId: String

    @Column(name = "nonce", nullable = false)
    lateinit var nonce: String

    @Column(name = "audience", nullable = false)
    lateinit var audience: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "result_json")
    var resultJson: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant
}
