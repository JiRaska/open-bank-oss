// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** ADR-0352 phase 3: one committed legacy decision queued for the product-IR shadow (V21). */
@Entity
@Table(name = "interest_product_shadow_input")
class ProductShadowInputEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "source_kind", nullable = false)
    lateinit var sourceKind: String

    @Column(name = "source_id", nullable = false, columnDefinition = "uuid")
    lateinit var sourceId: UUID

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

    @Column(name = "rule_id")
    var ruleId: String? = null

    @Column(name = "legacy_amount", precision = 20, scale = 6)
    var legacyAmount: BigDecimal? = null

    @Column(name = "ir_amount", precision = 20, scale = 6)
    var irAmount: BigDecimal? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "completed_at")
    var completedAt: Instant? = null
}

/** ADR-0352 §6: interest's immutable local copy of a pinned revision's product IR (V21). */
@Entity
@Table(name = "interest_product_revision_ir")
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
