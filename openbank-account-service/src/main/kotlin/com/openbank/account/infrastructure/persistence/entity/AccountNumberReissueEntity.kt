// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** V35 `account_number_reissue`: one audit row per re-issued account number. */
@Entity
@Table(name = "account_number_reissue")
class AccountNumberReissueEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "account_id", nullable = false, updatable = false)
    lateinit var accountId: UUID

    @Column(name = "old_account_number", nullable = false, updatable = false, length = 34)
    lateinit var oldAccountNumber: String

    @Column(name = "new_account_number", nullable = false, updatable = false, length = 34)
    lateinit var newAccountNumber: String

    @Column(name = "reason", nullable = false, updatable = false, length = 64)
    lateinit var reason: String

    @Column(name = "reissued_at", nullable = false, updatable = false)
    lateinit var reissuedAt: Instant
}
