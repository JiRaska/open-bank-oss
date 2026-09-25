// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * EVERY column is named explicitly, single-word ones included: this service sets no
 * CamelCaseToUnderscoresNamingStrategy, so an implicit `createdAt` would resolve to `createdat`
 * (entity-column-names gate; consent-service's SuppressionEntity 500'd on every call that way).
 */

@Entity
@Table(name = "counterparties")
class CounterpartyEntity : PanacheEntity() {
    @Column(name = "counterparty_id", nullable = false, unique = true)
    lateinit var counterpartyId: String

    @Column(name = "name", nullable = false)
    lateinit var name: String

    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "limit_czk")
    var limitCzk: BigDecimal? = null

    @Column(name = "limit_eur")
    var limitEur: BigDecimal? = null

    @Column(name = "synthetic", nullable = false)
    var synthetic: Boolean = true
}

@Entity
@Table(name = "deals")
class DealEntity : PanacheEntity() {
    @Column(name = "deal_id", nullable = false, unique = true)
    lateinit var dealId: UUID

    @Column(name = "product", nullable = false)
    lateinit var product: String

    @Column(name = "counterparty_id", nullable = false)
    lateinit var counterpartyId: String

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "principal", nullable = false)
    lateinit var principal: BigDecimal

    @Column(name = "rate", nullable = false)
    lateinit var rate: BigDecimal

    @Column(name = "trade_date", nullable = false)
    lateinit var tradeDate: LocalDate

    @Column(name = "value_date", nullable = false)
    lateinit var valueDate: LocalDate

    @Column(name = "maturity_date", nullable = false)
    lateinit var maturityDate: LocalDate

    @Column(name = "state", nullable = false)
    lateinit var state: String

    @Column(name = "created_by", nullable = false)
    lateinit var createdBy: String

    @Column(name = "created_by_type", nullable = false)
    lateinit var createdByType: String

    @Column(name = "submitted_by")
    var submittedBy: String? = null

    @Column(name = "submitted_by_type")
    var submittedByType: String? = null

    @Column(name = "approved_by")
    var approvedBy: String? = null

    @Column(name = "approved_by_type")
    var approvedByType: String? = null

    @Column(name = "limit_amount")
    var limitAmount: BigDecimal? = null

    @Column(name = "limit_exposure_before")
    var limitExposureBefore: BigDecimal? = null

    @Column(name = "limit_deal_amount")
    var limitDealAmount: BigDecimal? = null

    @Column(name = "rationale", columnDefinition = "TEXT")
    var rationale: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

/** Append-only timeline: one row per transition, never updated. */
@Entity
@Table(name = "deal_transitions")
class DealTransitionEntity : PanacheEntity() {
    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "seq", nullable = false)
    var seq: Int = 0

    @Column(name = "from_state")
    var fromState: String? = null

    @Column(name = "to_state", nullable = false)
    lateinit var toState: String

    @Column(name = "actor_id", nullable = false)
    lateinit var actorId: String

    @Column(name = "actor_type", nullable = false)
    lateinit var actorType: String

    @Column(name = "at", nullable = false)
    lateinit var at: Instant

    @Column(name = "note", columnDefinition = "TEXT")
    var note: String? = null
}

/** The ledger journal each value-moving event produced; `idempotency_key` is unique. */
@Entity
@Table(name = "deal_journals")
class DealJournalEntity : PanacheEntity() {
    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "event", nullable = false)
    lateinit var event: String

    @Column(name = "idempotency_key", nullable = false, unique = true)
    lateinit var idempotencyKey: String

    @Column(name = "journal_id", nullable = false)
    lateinit var journalId: UUID

    @Column(name = "posted_at", nullable = false)
    lateinit var postedAt: Instant
}

@Entity
@Table(name = "treasury_outbox")
class TreasuryOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
