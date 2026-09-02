// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.MAX_RESERVATION_IDEMPOTENCY_KEY_LENGTH
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationOperationType
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "delegation_spend_reservations")
class SpendReservationEntity : PanacheEntityBase() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "grant_id", nullable = false, updatable = false)
    lateinit var grantId: UUID

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false, length = CURRENCY_CODE_LENGTH)
    lateinit var currency: String

    @Column(
        name = "idempotency_key",
        nullable = false,
        updatable = false,
        length = MAX_RESERVATION_IDEMPOTENCY_KEY_LENGTH,
    )
    lateinit var idempotencyKey: String

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false)
    lateinit var operationType: SpendReservationOperationType

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    lateinit var state: SpendReservationState

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "settled_at")
    var settledAt: OffsetDateTime? = null

    fun toDomain(): SpendReservation = SpendReservation(
        id = id,
        grantId = grantId,
        amount = toMoney(amount, currency),
        idempotencyKey = idempotencyKey,
        operationType = operationType,
        state = state,
        createdAt = createdAt,
        settledAt = settledAt,
    )

    companion object {
        const val CURRENCY_CODE_LENGTH = 3

        /**
         * The column is NUMERIC(20,6) — the convention this schema already uses for the three
         * ceiling columns — while [Money] refuses a scale wider than the currency's minor unit.
         * Re-scaling on the way out is therefore mandatory, not cosmetic: without it every
         * rehydration of a CZK row read back at scale 6 would throw. HALF_EVEN never actually
         * rounds anything here, because nothing writes more precision than the currency has.
         */
        fun toMoney(amount: BigDecimal, currency: String): Money {
            val code = CurrencyCode.of(currency.trim())
            return Money(amount.setScale(code.defaultFractionDigits, RoundingMode.HALF_EVEN), code)
        }

        fun fromDomain(r: SpendReservation): SpendReservationEntity = SpendReservationEntity().apply {
            id = r.id
            grantId = r.grantId
            amount = r.amount.amount
            currency = r.amount.currency.code
            idempotencyKey = r.idempotencyKey
            operationType = r.operationType
            state = r.state
            createdAt = r.createdAt
            settledAt = r.settledAt
        }
    }
}
