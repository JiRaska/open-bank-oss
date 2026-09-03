// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Fiscal-year close record (ADR-0078 D5, increment 1); one row per fiscal year. */
@Entity
@Table(name = "ledger_year_close")
class YearCloseEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID.randomUUID()

    @Column(name = "fiscal_year", nullable = false, unique = true)
    var fiscalYear: Int = 0

    @Column(name = "status", nullable = false)
    var status: String = "DRAFT"

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

    /** The maker: OIDC subject that created/last-refreshed the DRAFT (four-eyes author, #869). */
    @Column(name = "drafted_by")
    var draftedBy: String? = null

    @Column(name = "attested_by")
    var attestedBy: String? = null

    @Column(name = "attested_at")
    var attestedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
