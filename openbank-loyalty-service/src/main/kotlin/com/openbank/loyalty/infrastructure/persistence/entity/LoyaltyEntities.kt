// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import com.openbank.loyalty.domain.BenefitGrant
import com.openbank.loyalty.domain.BenefitGrantStatus
import com.openbank.loyalty.domain.LeafEarnSource
import com.openbank.loyalty.domain.LeafEntryType
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.Leaves
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Every column is named explicitly, and this service configures NO
 * `hibernate-orm.physical-naming-strategy` — the two facts belong together. Hibernate's implicit
 * name for a property is the property name verbatim and Postgres folds it to lower case, so
 * `occurredAt` would resolve to `occurredat` while the migration writes `occurred_at`. It is
 * wrong for every multi-word property and right for every single-word one, which is why it reads
 * as consistent; consent-service shipped it and answered 500 on every call to one endpoint from
 * the day it shipped. `check-entity-column-names.py` enforces the pairing.
 */
@Entity
@Table(name = "leaf_ledger_entry")
class LeafLedgerEntryEntity {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "entry_type", nullable = false)
    lateinit var entryType: String

    @Column(name = "leaves", nullable = false)
    var leaves: Int = 0

    @Column(name = "remaining_leaves", nullable = false)
    var remainingLeaves: Int = 0

    @Column(name = "earn_source_id")
    var earnSourceId: String? = null

    @Column(name = "benefit_id")
    var benefitId: String? = null

    @Column(name = "rule_version", nullable = false)
    lateinit var ruleVersion: String

    @Column(name = "correlation_event_id", nullable = false)
    lateinit var correlationEventId: UUID

    @Column(name = "occurred_at", nullable = false)
    lateinit var occurredAt: Instant

    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    fun toDomain(): LeafLedgerEntry = LeafLedgerEntry(
        id = id,
        partyId = partyId,
        type = LeafEntryType.valueOf(entryType),
        leaves = Leaves.of(leaves),
        remaining = Leaves.of(remainingLeaves),
        earnSource = earnSourceId?.let { LeafEarnSource.byId(it) },
        benefitId = benefitId,
        ruleVersion = ruleVersion,
        correlationEventId = correlationEventId,
        occurredAt = occurredAt,
        expiresAt = expiresAt,
    )

    companion object {
        fun from(entry: LeafLedgerEntry): LeafLedgerEntryEntity = LeafLedgerEntryEntity().also {
            it.id = entry.id
            it.partyId = entry.partyId
            it.entryType = entry.type.name
            it.leaves = entry.leaves.value
            it.remainingLeaves = entry.remaining.value
            it.earnSourceId = entry.earnSource?.id
            it.benefitId = entry.benefitId
            it.ruleVersion = entry.ruleVersion
            it.correlationEventId = entry.correlationEventId
            it.occurredAt = entry.occurredAt
            it.expiresAt = entry.expiresAt
        }
    }
}

@Entity
@Table(name = "benefit_grant")
class BenefitGrantEntity {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "benefit_id", nullable = false)
    lateinit var benefitId: String

    @Column(name = "price_leaves", nullable = false)
    var priceLeaves: Int = 0

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "reserved_at", nullable = false)
    lateinit var reservedAt: Instant

    @Column(name = "granted_at")
    var grantedAt: Instant? = null

    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    fun toDomain(): BenefitGrant = BenefitGrant(
        id = id,
        partyId = partyId,
        benefitId = benefitId,
        price = Leaves.of(priceLeaves),
        status = BenefitGrantStatus.valueOf(status),
        idempotencyKey = idempotencyKey,
        reservedAt = reservedAt,
        grantedAt = grantedAt,
        expiresAt = expiresAt,
    )

    companion object {
        fun from(grant: BenefitGrant): BenefitGrantEntity = BenefitGrantEntity().also {
            it.id = grant.id
            it.partyId = grant.partyId
            it.benefitId = grant.benefitId
            it.priceLeaves = grant.price.value
            it.status = grant.status.name
            it.idempotencyKey = grant.idempotencyKey
            it.reservedAt = grant.reservedAt
            it.grantedAt = grant.grantedAt
            it.expiresAt = grant.expiresAt
        }
    }
}

/** `claimed_at` is per-service, same reasoning as `AccountOutboxEntity` (#1201). */
@Entity
@Table(name = "loyalty_outbox")
class LoyaltyOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
