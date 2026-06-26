// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Hibernate-reactive Panache entity for the `settlements` table (V1 migration). The primary key is
 * the domain UUID (not a surrogate Long), so this extends PanacheEntityBase with an explicit @Id —
 * see SettlementPanacheRepo (PanacheRepositoryBase<_, UUID>).
 */
@Entity
@Table(name = "settlements")
class SettlementEntity : io.quarkus.hibernate.reactive.panache.PanacheEntityBase() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "payer_account_id", nullable = false, updatable = false)
    lateinit var payerAccountId: UUID

    @Column(name = "payee_account_id", nullable = false, updatable = false)
    lateinit var payeeAccountId: UUID

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    lateinit var currency: String

    // status + updated_at are the only mutable columns (the settlement lifecycle).
    @Column(name = "status", nullable = false, length = 32)
    lateinit var status: String

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
