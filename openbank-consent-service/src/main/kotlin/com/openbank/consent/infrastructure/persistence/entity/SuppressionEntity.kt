// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "suppressions")
class SuppressionEntity {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var partyId: UUID

    @Column(nullable = false, length = 10)
    lateinit var scope: String

    @Column(length = 255)
    var value: String? = null

    @Column(nullable = false, length = 30)
    lateinit var reasonCode: String

    @Column(nullable = false, length = 100)
    lateinit var source: String

    @Column(nullable = false)
    lateinit var createdBy: String

    @Column(nullable = false)
    lateinit var createdAt: OffsetDateTime

    var revokedAt: OffsetDateTime? = null

    var revokedBy: String? = null
}
