// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * Saved-payee row (TOP-10 #5). Panache's own auto Long `id` is the DB PK (unused by the domain,
 * same convention as [PartyDocumentEntity]'s `documentId`); [payeeId] is the domain-facing UUID.
 * `partyId` is deliberately NOT unique alone (one party has many payees) — the dedup key is
 * `(party_id, iban)`, enforced by [UniqueConstraint] so a re-save always finds and updates the
 * same row rather than the app's own dedup-by-IBAN rule being the only thing standing between a
 * customer and duplicate rows.
 */
@Entity
@Table(
    name = "party_payees",
    indexes = [Index(name = "idx_party_payees_party_id", columnList = "party_id")],
    uniqueConstraints = [UniqueConstraint(name = "uq_party_payees_party_iban", columnNames = ["party_id", "iban"])],
)
class PartyPayeeEntity : PanacheEntity() {
    @Column(name = "payee_id", nullable = false, unique = true)
    lateinit var payeeId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "name", nullable = false, length = 120)
    lateinit var name: String

    @Column(name = "iban", nullable = false, length = 34)
    lateinit var iban: String

    @Column(name = "bic", length = 11)
    var bic: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}
