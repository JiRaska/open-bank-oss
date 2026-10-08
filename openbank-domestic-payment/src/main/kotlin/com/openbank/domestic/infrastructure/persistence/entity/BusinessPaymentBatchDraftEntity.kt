// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "business_payment_batch_drafts")
class BusinessPaymentBatchDraftEntity {
    @Id
    lateinit var id: UUID

    @Column(name = "entity_party_id", nullable = false)
    lateinit var entityPartyId: UUID

    @Column(name = "actor_party_id", nullable = false)
    lateinit var actorPartyId: UUID

    @Column(name = "updated_by_party_id", nullable = false)
    lateinit var updatedByPartyId: UUID

    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "request_hash", nullable = false)
    lateinit var requestHash: String

    @Column(name = "original_response_json", nullable = false, columnDefinition = "text")
    lateinit var originalResponseJson: String

    @Column(name = "debtor_account_id", nullable = false)
    lateinit var debtorAccountId: UUID

    @Column(name = "items_json", nullable = false, columnDefinition = "text")
    lateinit var itemsJson: String

    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long = 0

    @Column(name = "item_count", nullable = false)
    var itemCount: Int = 0

    @Version
    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
