// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.portfolio

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/* Every column named explicitly (entity-column-names gate): this service sets no naming strategy. */

@Entity
@Table(name = "portfolio_statements")
class PortfolioStatementEntity : PanacheEntity() {
    @Column(name = "statement_uuid", nullable = false, unique = true)
    lateinit var statementUuid: UUID

    @Column(name = "idempotency_key", nullable = false, unique = true)
    lateinit var idempotencyKey: String

    @Column(name = "entity", nullable = false)
    lateinit var entity: String

    @Column(name = "statement_id")
    var statementId: String? = null

    @Column(name = "safekeeping_account", nullable = false)
    lateinit var safekeepingAccount: String

    @Column(name = "statement_date", nullable = false)
    lateinit var statementDate: LocalDate

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "version", nullable = false)
    var version: Int = 0

    @Column(name = "supersedes")
    var supersedes: UUID? = null

    @Column(name = "superseded_by")
    var supersededBy: UUID? = null

    @Column(name = "superseded_at")
    var supersededAt: Instant? = null

    @Column(name = "sha256", nullable = false)
    lateinit var sha256: String

    @Column(name = "uploaded_by", nullable = false)
    lateinit var uploadedBy: String

    @Column(name = "uploaded_at", nullable = false)
    lateinit var uploadedAt: Instant
}

@Entity
@Table(name = "portfolio_holdings")
class PortfolioHoldingEntity : PanacheEntity() {
    @Column(name = "statement_uuid", nullable = false)
    lateinit var statementUuid: UUID

    @Column(name = "entity", nullable = false)
    lateinit var entity: String

    @Column(name = "statement_date", nullable = false)
    lateinit var statementDate: LocalDate

    @Column(name = "isin", nullable = false)
    lateinit var isin: String

    @Column(name = "cfi", nullable = false)
    lateinit var cfi: String

    @Column(name = "instrument_class", nullable = false)
    lateinit var instrumentClass: String

    @Column(name = "quantity", nullable = false)
    lateinit var quantity: BigDecimal

    @Column(name = "valuation", nullable = false)
    lateinit var valuation: BigDecimal

    @Column(name = "valuation_currency", nullable = false)
    lateinit var valuationCurrency: String
}
