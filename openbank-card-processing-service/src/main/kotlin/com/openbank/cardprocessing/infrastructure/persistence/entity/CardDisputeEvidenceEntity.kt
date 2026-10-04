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

/** One row of a case's APPEND-ONLY evidence history. Every column name spelled out. */
@Entity
@Table(name = "card_dispute_evidence")
class CardDisputeEvidenceEntity {
    @Id
    @Column(name = "id")
    lateinit var id: UUID

    @Column(name = "dispute_id")
    lateinit var disputeId: UUID

    @Column(name = "document_reference")
    lateinit var documentReference: String

    @Column(name = "note")
    var note: String? = null

    @Column(name = "scheme_status")
    lateinit var schemeStatus: String

    @Column(name = "submitted_at")
    lateinit var submittedAt: Instant
}
