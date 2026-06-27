// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Local projection of an account, built from the account-service `AccountCreated` stream. The only
 * enumeration source for the scheduled monthly period-close (account-service exposes no "all
 * accounts" endpoint and owns its own DB — ADR-0002).
 */
@Entity
@Table(name = "account_registry")
class AccountRegistryEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "currency", nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "registered_at", nullable = false)
    lateinit var registeredAt: Instant
}
