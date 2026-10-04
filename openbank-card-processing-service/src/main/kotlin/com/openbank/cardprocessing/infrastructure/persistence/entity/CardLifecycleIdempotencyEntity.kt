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
 * An idempotency reservation (see `V3__token_and_dispute_lifecycle.sql`). Inserted only through
 * `INSERT ... ON CONFLICT DO NOTHING` in [com.openbank.cardprocessing.infrastructure.persistence.repository.LifecycleIdempotencyRepositoryImpl];
 * the entity exists for the read.
 */
@Entity
@Table(name = "card_lifecycle_idempotency")
class CardLifecycleIdempotencyEntity {
    @Id
    @Column(name = "reservation_key")
    lateinit var reservationKey: String

    @Column(name = "operation")
    lateinit var operation: String

    @Column(name = "fingerprint")
    lateinit var fingerprint: String

    @Column(name = "state")
    lateinit var state: String

    @Column(name = "result_id")
    var resultId: UUID? = null

    @Column(name = "created_at")
    lateinit var createdAt: Instant

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant
}
