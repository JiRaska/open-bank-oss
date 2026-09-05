// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "accounts",
    indexes = [
        Index(name = "idx_accounts_party_id", columnList = "party_id"),
        Index(name = "idx_accounts_account_number", columnList = "account_number", unique = true),
        Index(name = "idx_accounts_status", columnList = "status"),
    ],
)
class AccountEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "account_number", nullable = false, unique = true, length = 34)
    lateinit var accountNumber: String

    @Column(name = "account_type", nullable = false, length = 20)
    lateinit var accountType: String

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "product_id", nullable = false)
    lateinit var productId: UUID

    @Column(name = "currency_code", nullable = false, length = 3)
    lateinit var currencyCode: String

    @Column(name = "status", nullable = false, length = 20)
    lateinit var status: String

    @Column(name = "signing_rule", nullable = false, length = 20)
    var signingRule: String = "SINGLE"

    @Column(name = "opened_at", nullable = false)
    lateinit var openedAt: Instant

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    /** Timestamp of the sanctions screen result — written at account opening (ADR-0032 §C, V11). */
    @Column(name = "sanctions_screened_at")
    var sanctionsScreenedAt: Instant? = null

    /** Sanctions screen outcome: CLEAR | HIT | REVIEW (V11). */
    @Column(name = "sanctions_status", length = 20)
    var sanctionsStatus: String? = null

    /** Account holder name stored for downstream display / statements (V12). Nulled out on GDPR Art. 17 erasure. */
    @Column(name = "legal_name", length = 255)
    var legalName: String? = null

    /** Optional savings goal (ADR-0153, V13). Set iff goalTargetMinorUnits is non-null. */
    @Column(name = "goal_name", length = 120)
    var goalName: String? = null

    @Column(name = "goal_target_minor_units")
    var goalTargetMinorUnits: Long? = null

    @Column(name = "goal_target_date")
    var goalTargetDate: LocalDate? = null

    /** Customer-chosen display label (V20). Null means "use the account-type default name". */
    @Column(name = "nickname", length = 60)
    var nickname: String? = null

    /** Stamped from the injected [java.time.Clock] in the repository layer (ADR-0100 — no wall-clock reads here). */
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

@Entity
@Table(
    name = "account_pockets",
    indexes = [
        Index(name = "idx_account_pockets_account", columnList = "account_id"),
    ],
)
class AccountPocketEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "currency_code", nullable = false, length = 3)
    lateinit var currencyCode: String

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false

    @Column(name = "status", nullable = false, length = 20)
    lateinit var status: String

    @Column(name = "opened_at", nullable = false)
    lateinit var openedAt: Instant

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    /** Stamped from the injected [java.time.Clock] in the repository layer (ADR-0100 — no wall-clock reads here). */
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

/**
 * Transactional idempotency for account opening (#465): the key commits atomically with the
 * account + primary pocket insert, so a concurrent duplicate submission dies on the primary
 * key instead of opening a second account (the Redis record in the REST layer is only a
 * response-replay cache and is check-then-act under concurrency).
 */
@Entity
@Table(name = "account_idempotency")
class AccountIdempotencyEntity : PanacheEntityBase {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 255)
    lateinit var idempotencyKey: String

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
