// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "operator_messages")
class OperatorMessageEntity : PanacheEntity() {
    @Column(name = "message_id", nullable = false, unique = true)
    lateinit var messageId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(nullable = false)
    lateinit var template: String

    @Column(name = "reference_id", nullable = false)
    lateinit var referenceId: String

    @Column(nullable = false)
    lateinit var purpose: String

    @Column(nullable = false)
    lateinit var status: String

    @Column(name = "maker_id", nullable = false)
    lateinit var makerId: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
