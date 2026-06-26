// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payment_sagas")
class PaymentSagaEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID.randomUUID()

    @Column(name = "transaction_id", nullable = false)
    var transactionId: UUID = UUID.randomUUID()

    @Column(name = "state", nullable = false)
    var state: String = "STARTED"

    @Column(name = "idempotency_key", nullable = false, unique = true)
    var idempotencyKey: String = ""

    @Column(name = "failure_reason")
    var failureReason: String? = null

    @Column(name = "compensation_reason")
    var compensationReason: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
