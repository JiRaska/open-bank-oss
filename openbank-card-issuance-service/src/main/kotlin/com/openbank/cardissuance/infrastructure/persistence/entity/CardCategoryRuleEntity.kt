// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Composite key: one rule per (card, category). */
data class CardCategoryRuleId(var cardId: UUID = UUID(0, 0), var category: String = "") : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Entity
@Table(name = "card_category_rules")
@IdClass(CardCategoryRuleId::class)
class CardCategoryRuleEntity : PanacheEntityBase {
    @Id
    @Column(name = "card_id")
    var cardId: UUID = UUID(0, 0)

    @Id
    @Column(name = "category")
    var category: String = ""

    @Column(name = "blocked", nullable = false)
    var blocked: Boolean = false

    /** Null means uncapped. Zero is a real cap of nothing, which is a different statement. */
    @Column(name = "monthly_limit_minor_units")
    var monthlyLimitMinorUnits: Long? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
