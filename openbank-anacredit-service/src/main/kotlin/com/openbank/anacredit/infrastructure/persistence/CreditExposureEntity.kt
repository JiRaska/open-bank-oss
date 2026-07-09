// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Persisted AnaCredit credit exposure (ADR-0037 v2). Reactive Panache entity (the fleet standard —
 * see openbank-product-catalog). Scalar columns mirror [com.openbank.anacredit.domain.model.CreditExposure]
 * one-to-one; the row is relational (not document-shaped) because every field is a first-class
 * report/threshold attribute, not opaque payload.
 *
 * Keyed by [instrumentId] — a re-submitted exposure for the same instrument replaces the prior
 * reference-date snapshot, mirroring the previous in-memory repository's upsert semantics.
 */
@Entity
@Table(name = "credit_exposures")
class CreditExposureEntity : PanacheEntityBase {

    companion object : PanacheCompanionBase<CreditExposureEntity, String>

    @Id
    @Column(name = "instrument_id", nullable = false)
    lateinit var instrumentId: String

    @Column(name = "debtor_id", nullable = false)
    lateinit var debtorId: String

    @Column(name = "debtor_type", nullable = false)
    lateinit var debtorType: String

    @Column(name = "instrument_type", nullable = false)
    lateinit var instrumentType: String

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "committed_amount", nullable = false)
    lateinit var committedAmount: BigDecimal

    @Column(name = "drawn_amount", nullable = false)
    lateinit var drawnAmount: BigDecimal

    @Column(name = "committed_amount_eur", nullable = false)
    lateinit var committedAmountEur: BigDecimal

    @Column(name = "arrears_amount", nullable = false)
    lateinit var arrearsAmount: BigDecimal

    @Column(name = "defaulted", nullable = false)
    var defaulted: Boolean = false

    @Column(name = "origination_date", nullable = false)
    lateinit var originationDate: LocalDate

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
