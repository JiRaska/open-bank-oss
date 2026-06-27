// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Audit row for one ADR-0039 Phase A reconciliation run. The per-currency breakdown is stored as a
 * JSON document in [currencies] so the run is self-contained without a child table.
 */
@Entity
@Table(name = "balance_reconciliation")
class BalanceReconciliationEntity : PanacheEntity() {

    @Column(name = "as_of", nullable = false)
    lateinit var asOf: LocalDate

    @Column(name = "generated_at", nullable = false)
    lateinit var generatedAt: OffsetDateTime

    @Column(nullable = false, precision = 19, scale = 4)
    var tolerance: BigDecimal = BigDecimal.ZERO

    @Column(name = "has_drift", nullable = false)
    var hasDrift: Boolean = false

    @Column(name = "drifted_currencies", nullable = false)
    var driftedCurrencies: String = ""

    @Column(nullable = false, columnDefinition = "text")
    lateinit var currencies: String
}
