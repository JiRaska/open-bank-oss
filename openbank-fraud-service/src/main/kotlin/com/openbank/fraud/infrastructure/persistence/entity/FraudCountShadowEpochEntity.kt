// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "fraud_count_shadow_epoch")
class FraudCountShadowEpochEntity {
    @Id
    @Column(name = "epoch_number")
    var number: Long = 0

    @Column(name = "previous_epoch_number", updatable = false)
    var previousNumber: Long? = null

    @Column(name = "proposal_id", nullable = false, updatable = false)
    lateinit var proposalId: UUID

    @Column(name = "envelope_digest", nullable = false, updatable = false)
    lateinit var digest: String

    @Column(name = "envelope_json", nullable = false, updatable = false)
    lateinit var envelopeJson: String

    @Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: Instant

    @Column(name = "effective_at", nullable = false, updatable = false)
    lateinit var effectiveAt: Instant
}

@Entity
@Table(name = "fraud_count_shadow_epoch_head")
class FraudCountShadowEpochHeadEntity {
    @Id
    @Column(name = "singleton_id")
    var id: Int = 0

    @Column(name = "epoch_number")
    var number: Long? = null
}
