// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.util.UUID

/** Immutable line-level evidence belonging to one FROZEN statutory close (ADR-0096 D1). */
@Entity
@Table(name = "ledger_closed_period_trial_balance_line")
@IdClass(ClosedPeriodTrialBalanceLineId::class)
class ClosedPeriodTrialBalanceLineEntity : PanacheEntityBase {
    @Id
    @Column(name = "period_id")
    lateinit var periodId: UUID

    @Id
    @Column(name = "gl_account_id")
    lateinit var glAccountId: UUID

    @Id
    @Column(name = "currency", length = 3)
    lateinit var currency: String

    @Column(name = "code", nullable = false, length = 128)
    lateinit var code: String

    @Column(name = "name", nullable = false, length = 255)
    lateinit var name: String

    @Column(name = "account_type", nullable = false, length = 32)
    lateinit var accountType: String

    @Column(name = "total_debit", nullable = false)
    lateinit var totalDebit: BigDecimal

    @Column(name = "total_credit", nullable = false)
    lateinit var totalCredit: BigDecimal
}

data class ClosedPeriodTrialBalanceLineId(
    var periodId: UUID? = null,
    var glAccountId: UUID? = null,
    var currency: String? = null,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}
