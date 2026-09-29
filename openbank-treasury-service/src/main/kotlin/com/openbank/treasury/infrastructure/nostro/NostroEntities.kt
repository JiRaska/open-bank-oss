// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

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
@Table(name = "nostro_statements")
class NostroStatementEntity : PanacheEntity() {
    @Column(name = "statement_uuid", nullable = false, unique = true)
    lateinit var statementUuid: UUID

    @Column(name = "idempotency_key", nullable = false, unique = true)
    lateinit var idempotencyKey: String

    @Column(name = "statement_id", nullable = false)
    lateinit var statementId: String

    @Column(name = "iban", nullable = false)
    lateinit var iban: String

    @Column(name = "gl_code", nullable = false)
    lateinit var glCode: String

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "statement_date", nullable = false)
    lateinit var statementDate: LocalDate

    @Column(name = "opening_balance", nullable = false)
    lateinit var openingBalance: BigDecimal

    @Column(name = "closing_balance", nullable = false)
    lateinit var closingBalance: BigDecimal

    @Column(name = "sha256", nullable = false)
    lateinit var sha256: String

    @Column(name = "uploaded_by", nullable = false)
    lateinit var uploadedBy: String

    @Column(name = "uploaded_at", nullable = false)
    lateinit var uploadedAt: Instant
}

@Entity
@Table(name = "nostro_statement_entries")
class NostroStatementEntryEntity : PanacheEntity() {
    @Column(name = "statement_uuid", nullable = false)
    lateinit var statementUuid: UUID

    @Column(name = "sequence", nullable = false)
    var sequence: Int = 0

    @Column(name = "amount", nullable = false)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "direction", nullable = false)
    lateinit var direction: String

    @Column(name = "booking_date", nullable = false)
    lateinit var bookingDate: LocalDate

    @Column(name = "reference")
    var reference: String? = null
}
