// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** ADR-0352 phase 3: one queued legacy fee decision awaiting the shadow comparator (V10). */
@Entity
@Table(name = "billing_product_shadow_input")
class ProductShadowInputEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "assessment_id", nullable = false, columnDefinition = "uuid")
    lateinit var assessmentId: UUID

    @Column(name = "cycle_id", nullable = false)
    lateinit var cycleId: String

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: String

    @Column(name = "currency", length = 3, nullable = false)
    lateinit var currency: String

    @Column(name = "product_revision_id", nullable = false, columnDefinition = "uuid")
    lateinit var productRevisionId: UUID

    @Column(name = "balance", precision = 20, scale = 4)
    var balance: BigDecimal? = null

    @Column(name = "monthly_turnover", precision = 20, scale = 4)
    var monthlyTurnover: BigDecimal? = null

    @Column(name = "aggregate_pocket_balance", precision = 20, scale = 4)
    var aggregatePocketBalance: BigDecimal? = null

    @Column(name = "segment")
    var segment: String? = null

    @Column(name = "legacy_fees", nullable = false)
    lateinit var legacyFees: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    @Column(name = "next_attempt_at", nullable = false)
    lateinit var nextAttemptAt: Instant

    @Column(name = "claimed_at")
    var claimedAt: Instant? = null

    @Column(name = "outcome")
    var outcome: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "completed_at")
    var completedAt: Instant? = null
}

/** ADR-0352 §6: billing's immutable local copy of a pinned revision's product IR (V10). */
@Entity
@Table(name = "billing_product_revision_ir")
class ProductRevisionIrEntity : PanacheEntityBase() {
    @Id
    @Column(name = "revision_id", columnDefinition = "uuid")
    lateinit var revisionId: UUID

    @Column(name = "has_ir", nullable = false)
    var hasIr: Boolean = false

    @Column(name = "revision_hash")
    var revisionHash: String? = null

    @Column(name = "ir_json")
    var irJson: String? = null

    @Column(name = "key_id")
    var keyId: String? = null

    @Column(name = "algorithm")
    var algorithm: String? = null

    @Column(name = "signature")
    var signature: String? = null

    @Column(name = "fetched_at", nullable = false)
    lateinit var fetchedAt: Instant
}
