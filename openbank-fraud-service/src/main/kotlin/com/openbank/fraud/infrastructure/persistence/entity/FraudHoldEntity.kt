// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Surrogate id + unique `party_id` business key — same convention as engagement-service's
 * `AdverseStateEntity`/`party_adverse_state`: a `persist()` on an app-assigned id is INSERT-only
 * in Hibernate Reactive (a non-null id can't distinguish transient from detached), so a natural
 * `party_id` PK would 500 at flush on every re-raise of an already-active hold. The repository
 * finds-by-business-key first and only ever persists a genuinely new row (see
 * `FraudHoldRepositoryImpl.raise`).
 */
@Entity
@Table(name = "fraud_hold")
class FraudHoldEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false, updatable = false)
    lateinit var partyId: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(nullable = false)
    var active: Boolean = true

    @Column(nullable = false)
    lateinit var reason: String

    @Column(name = "rule_version", nullable = false)
    lateinit var ruleVersion: String

    @Column(name = "set_at", nullable = false)
    lateinit var setAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant
}
