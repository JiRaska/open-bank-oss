// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.persistence.entity

import com.openbank.billing.domain.PostingStatus
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * One assessment run for a `(cycleId, accountId, currency)` (ADR-0143 phase 2c). Explicit UUID
 * `@Id` on [PanacheEntityBase] (not the Hibernate-sequence-backed `PanacheEntity`) — mirrors
 * `InterestEntities.kt` — so no `<table>_seq` migration is needed for this table.
 */
@Entity
@Table(
    name = "billing_cycle_assessment",
    uniqueConstraints = [UniqueConstraint(columnNames = ["cycle_id", "account_id", "currency"])],
)
class BillingCycleAssessmentEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "cycle_id", nullable = false)
    lateinit var cycleId: String

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: String

    @Column(name = "currency", length = 3, nullable = false)
    lateinit var currency: String

    @Column(name = "skipped", nullable = false)
    var skipped: Boolean = false

    @Column(name = "skip_reason")
    var skipReason: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

/**
 * One assessed fee (ADR-0143 phase 2c) — the persisted form of
 * [com.openbank.billing.domain.AssessedFee], carrying the posting lifecycle
 * ([com.openbank.billing.domain.PostingStatus]) once the outbox/ledger leg lands a result.
 */
@Entity
@Table(
    name = "assessed_fee",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["idempotency_key"]),
        UniqueConstraint(columnNames = ["cycle_id", "account_id", "fee_id", "currency"]),
    ],
)
class AssessedFeeEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "assessment_id", nullable = false, columnDefinition = "uuid")
    lateinit var assessmentId: UUID

    @Column(name = "cycle_id", nullable = false)
    lateinit var cycleId: String

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: String

    @Column(name = "fee_id", nullable = false)
    lateinit var feeId: String

    @Column(name = "fee_name", nullable = false)
    lateinit var feeName: String

    @Column(name = "currency", length = 3, nullable = false)
    lateinit var currency: String

    @Column(name = "charged_amount", precision = 20, scale = 4, nullable = false)
    var chargedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "waived", nullable = false)
    var waived: Boolean = false

    @Column(name = "waive_reason", nullable = false)
    lateinit var waiveReason: String

    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "posting_status", nullable = false)
    @Enumerated(EnumType.STRING)
    lateinit var postingStatus: PostingStatus

    @Column(name = "journal_id", columnDefinition = "uuid")
    var journalId: UUID? = null

    @Column(name = "posted_at")
    var postedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
