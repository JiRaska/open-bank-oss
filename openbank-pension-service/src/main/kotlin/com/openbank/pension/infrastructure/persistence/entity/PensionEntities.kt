// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * EVERY column is named explicitly. This service sets no physical naming strategy, so an implicit
 * multi-word name would resolve to a folded identifier no migration creates (entity-column-names).
 */
@Entity
@Table(name = "pension_contracts")
class PensionContractEntity : PanacheEntity() {
    @Column(name = "contract_id", nullable = false, unique = true)
    lateinit var contractId: UUID

    @Column(name = "participant_party_id", nullable = false)
    lateinit var participantPartyId: UUID

    @Column(name = "product_line", nullable = false)
    lateinit var productLine: String

    @Column(name = "jurisdiction", nullable = false)
    lateinit var jurisdiction: String

    @Column(name = "pack_version", nullable = false)
    var packVersion: Int = 0

    @Column(name = "provider_entity_id", nullable = false)
    lateinit var providerEntityId: UUID

    @Column(name = "provider_type", nullable = false)
    lateinit var providerType: String

    @Column(name = "participant_birth_date", nullable = false)
    lateinit var participantBirthDate: LocalDate

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "contribution_amount", nullable = false)
    lateinit var contributionAmount: BigDecimal

    @Column(name = "employer_contribution_amount", nullable = false)
    lateinit var employerContributionAmount: BigDecimal

    @Column(name = "contribution_currency", nullable = false)
    lateinit var contributionCurrency: String

    @Column(name = "contribution_frequency", nullable = false)
    lateinit var contributionFrequency: String

    @Column(name = "beneficiaries", nullable = false)
    lateinit var beneficiaries: String

    @Column(name = "start_date")
    var startDate: LocalDate? = null

    @Column(name = "idempotency_key")
    var idempotencyKey: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

/** Append-only strategy election history; a change never overwrites the previous election. */
@Entity
@Table(name = "pension_strategy_elections")
class StrategyElectionEntity : PanacheEntity() {
    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "strategy_code", nullable = false)
    lateinit var strategyCode: String

    @Column(name = "effective_from", nullable = false)
    lateinit var effectiveFrom: LocalDate

    @Column(name = "elected_at", nullable = false)
    lateinit var electedAt: Instant
}
