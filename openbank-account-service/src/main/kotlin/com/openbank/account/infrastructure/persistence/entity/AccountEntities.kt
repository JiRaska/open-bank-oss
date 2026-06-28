// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
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

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.EPOCH
    }
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

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.EPOCH
    }
}
