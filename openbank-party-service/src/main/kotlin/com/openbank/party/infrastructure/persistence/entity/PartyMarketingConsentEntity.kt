// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tracks the currently ACTIVE marketing consent per party (ADR-0205 D4). Surrogate [PanacheEntity]
 * id, `partyId` as the unique business key — matching [PartyEntity]'s own convention, deliberately
 * NOT an application-assigned primary key, so both insert (new grant) and update (re-grant) go
 * through Panache's normal managed-entity dirty-checking rather than the `persist()`-is-INSERT-only
 * pitfall an assigned `@Id` would hit.
 */
@Entity
@Table(name = "party_marketing_consent")
class PartyMarketingConsentEntity : PanacheEntity() {
    @Column(name = "party_id", nullable = false, unique = true)
    lateinit var partyId: UUID

    @Column(name = "consent_id", nullable = false)
    lateinit var consentId: UUID

    @Column(name = "granted_at", nullable = false)
    lateinit var grantedAt: Instant
}
