// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * EVERY column is named explicitly, including the single-word ones.
 *
 * This service does NOT set `physical-naming-strategy: CamelCaseToUnderscoresNamingStrategy`, so
 * Hibernate's implicit name is the property name verbatim and Postgres folds an unquoted
 * identifier to lower case: `ownerPartyId` would resolve to `ownerpartyid` while the migration
 * wrote `owner_party_id`. That is right for every single-word property and wrong for every
 * multi-word one, so the class reads as internally consistent while half its columns do not
 * exist — consent-service's `SuppressionEntity` shipped exactly that and answered 500 on every
 * call from the day it shipped. Naming the single-word columns too removes the judgement call.
 */
@Entity
@Table(name = "declared_holdings")
class DeclaredHoldingEntity : PanacheEntity() {
    @Column(name = "holding_id", nullable = false, unique = true)
    lateinit var holdingId: UUID

    @Column(name = "owner_party_id", nullable = false)
    lateinit var ownerPartyId: UUID

    @Column(name = "holding_type", nullable = false)
    lateinit var holdingType: String

    @Column(name = "label", nullable = false)
    lateinit var label: String

    @Column(name = "valuation_amount", nullable = false)
    lateinit var valuationAmount: BigDecimal

    @Column(name = "valuation_currency", nullable = false)
    lateinit var valuationCurrency: String

    @Column(name = "valued_at", nullable = false)
    lateinit var valuedAt: LocalDate

    @Column(name = "valuation_source", nullable = false)
    lateinit var valuationSource: String

    @Column(name = "appraiser_reference")
    var appraiserReference: String? = null

    @Column(name = "ownership_share", nullable = false)
    lateinit var ownershipShare: BigDecimal

    @Column(name = "external_reference")
    var externalReference: String? = null

    @Column(name = "document_ids", nullable = false, columnDefinition = "TEXT")
    lateinit var documentIds: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "pledged_to_loan_id")
    var pledgedToLoanId: UUID? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

/**
 * One row per value ever asserted for a holding. Never updated, never deleted while the holding
 * lives: the point of the table is that the previous number still exists after the holding row has
 * moved on.
 */
@Entity
@Table(name = "declared_holding_valuations")
class DeclaredHoldingValuationEntity : PanacheEntity() {
    @Column(name = "holding_id", nullable = false)
    lateinit var holdingId: UUID

    @Column(name = "valuation_amount", nullable = false)
    lateinit var valuationAmount: BigDecimal

    @Column(name = "valuation_currency", nullable = false)
    lateinit var valuationCurrency: String

    @Column(name = "valued_at", nullable = false)
    lateinit var valuedAt: LocalDate

    @Column(name = "valuation_source", nullable = false)
    lateinit var valuationSource: String

    @Column(name = "appraiser_reference")
    var appraiserReference: String? = null

    /** When the BANK learned the value, as distinct from the date the value asserts. */
    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant
}

@Entity
@Table(name = "wealth_outbox")
class WealthOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
