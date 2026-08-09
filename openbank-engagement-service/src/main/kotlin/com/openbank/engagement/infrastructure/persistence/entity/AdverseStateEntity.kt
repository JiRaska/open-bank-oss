// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Surrogate id + unique (party_id, state) business key — same convention as
 * `PartyMarketingConsentEntity`, deliberately NOT an app-assigned `@Id` on the natural key: a
 * `persist()` on an assigned id is INSERT-only in Hibernate Reactive (a non-null id can't
 * distinguish transient from detached), so every re-set of an already-active state would 500 at
 * flush. The repository finds-by-business-key first and only ever persists a genuinely new row.
 */
@Entity
@Table(name = "party_adverse_state")
class AdverseStateEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false, updatable = false)
    lateinit var partyId: UUID

    @Column(nullable = false, updatable = false)
    lateinit var state: String

    @Column(name = "set_at", nullable = false)
    lateinit var setAt: Instant
}
