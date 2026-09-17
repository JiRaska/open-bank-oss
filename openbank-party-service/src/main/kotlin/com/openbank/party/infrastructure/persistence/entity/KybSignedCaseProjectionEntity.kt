// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "party_kyb_signed_case_projections")
class KybSignedCaseProjectionEntity {
    @Id
    @Column(name = "case_id", nullable = false)
    lateinit var caseId: UUID

    @Column(name = "payload_hash", nullable = false, length = 64)
    lateinit var payloadHash: String

    @Column(name = "mandate_count", nullable = false)
    var mandateCount: Int = 0

    @Column(name = "projected_at", nullable = false)
    lateinit var projectedAt: Instant
}
