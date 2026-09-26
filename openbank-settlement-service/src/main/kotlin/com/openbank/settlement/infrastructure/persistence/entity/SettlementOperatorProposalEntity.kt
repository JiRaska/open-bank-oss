// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.settlement.infrastructure.persistence.entity

import com.openbank.settlement.infrastructure.rest.CreateSettlementRequest
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "settlement_operator_proposals")
class SettlementOperatorProposalEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID

    @Column(name = "maker_id", nullable = false)
    lateinit var makerId: String

    @Column(nullable = false)
    lateinit var fingerprint: String

    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "payer_account_id", nullable = false)
    lateinit var payerAccountId: UUID

    @Column(name = "payee_account_id", nullable = false)
    lateinit var payeeAccountId: UUID

    // Decimal text retains the complete submitted value without a database scale conversion.
    @Column(nullable = false)
    lateinit var amount: String

    @Column(nullable = false)
    lateinit var currency: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: OffsetDateTime

    fun instruction() =
        CreateSettlementRequest(idempotencyKey, payerAccountId, payeeAccountId, BigDecimal(amount), currency)
}
