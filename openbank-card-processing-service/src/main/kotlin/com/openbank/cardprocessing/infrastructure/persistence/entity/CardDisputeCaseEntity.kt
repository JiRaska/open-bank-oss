// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Every column name is spelled out — see [CardAuthorizationEntity] for what an implicit name costs. */
@Entity
@Table(name = "card_dispute_cases")
class CardDisputeCaseEntity {
    @Id
    @Column(name = "id")
    lateinit var id: UUID

    @Column(name = "authorization_id")
    lateinit var authorizationId: UUID

    @Column(name = "card_id")
    lateinit var cardId: UUID

    @Column(name = "network_case_id")
    lateinit var networkCaseId: String

    @Column(name = "reason_code")
    lateinit var reasonCode: String

    @Column(name = "amount_minor_units")
    var amountMinorUnits: Long = 0

    @Column(name = "currency_code")
    lateinit var currencyCode: String

    @Column(name = "status")
    lateinit var status: String

    @Column(name = "scheme")
    lateinit var scheme: String

    /** The network's own status string, stored verbatim so an operator reads what the scheme said. */
    @Column(name = "scheme_status")
    lateinit var schemeStatus: String

    @Column(name = "respond_by_date")
    var respondByDate: LocalDate? = null

    @Column(name = "evidence_reference")
    var evidenceReference: String? = null

    @Column(name = "idempotency_key")
    lateinit var idempotencyKey: String

    @Column(name = "opened_at")
    lateinit var openedAt: Instant

    @Column(name = "updated_at")
    lateinit var updatedAt: Instant
}
