// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** `swift_institution_transfer_rejections` (V11): one row per refused release's payment id. */
@Entity
@Table(name = "swift_institution_transfer_rejections")
class InstitutionTransferRejectionEntity {
    @Id
    @field:Column(name = "payment_id")
    var paymentId: UUID = UUID.randomUUID()

    @field:Column(name = "idempotency_key")
    var idempotencyKey: String? = null

    @field:Column(name = "reason_code")
    var reasonCode: String = ""

    @field:Column(name = "reason")
    var reason: String = ""

    @field:Column(name = "synthetic")
    var synthetic: Boolean = false

    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.now()
}
