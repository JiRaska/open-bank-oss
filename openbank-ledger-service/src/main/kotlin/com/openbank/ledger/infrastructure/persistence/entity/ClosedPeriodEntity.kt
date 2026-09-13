// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Statutory period close (ADR-0096 D1); one row per (period type, period start). */
@Entity
@Table(name = "ledger_closed_period")
class ClosedPeriodEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "period_type", nullable = false)
    var periodType: String = "MONTH"

    @Column(name = "period_from", nullable = false)
    var periodFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "period_to", nullable = false)
    var periodTo: LocalDate = LocalDate.EPOCH

    @Column(name = "status", nullable = false)
    var status: String = "DRAFT"

    @Column(name = "evidence_state", nullable = false)
    var evidenceState: String = "NONE"

    @Column(name = "computed_at", nullable = false)
    var computedAt: Instant = Instant.now()

    @Column(name = "total_debits", nullable = false)
    var totalDebits: BigDecimal = BigDecimal.ZERO

    @Column(name = "total_credits", nullable = false)
    var totalCredits: BigDecimal = BigDecimal.ZERO

    @Column(name = "account_count", nullable = false)
    var accountCount: Int = 0

    /** SHA-256 (lowercase hex) of the canonical trial-balance JSON — the attestation anchor. */
    @Column(name = "content_hash", nullable = false, length = 64)
    var contentHash: String = ""

    /** The maker: OIDC subject that created or last refreshed the DRAFT (four-eyes). */
    @Column(name = "drafted_by")
    var draftedBy: String? = null

    @Column(name = "frozen_by")
    var frozenBy: String? = null

    @Column(name = "frozen_at")
    var frozenAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
