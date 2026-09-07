// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * journal_entries is RANGE-partitioned by entry_date, so its primary key is the
 * composite (id, entry_date). Hibernate needs an @IdClass mirroring that.
 */
@Entity
@Table(name = "journal_entries")
@IdClass(JournalEntryEntityId::class)
class JournalEntryEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID.randomUUID()

    @Id
    @Column(name = "entry_date")
    var entryDate: LocalDate = LocalDate.EPOCH

    @Column(name = "entry_number", nullable = false)
    var entryNumber: Long = 0

    @Column(name = "transaction_id", nullable = false)
    var transactionId: UUID = UUID.randomUUID()

    @Column(name = "value_date", nullable = false)
    var valueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "description")
    var description: String? = null

    @Column(name = "status", nullable = false)
    var status: String = "PENDING"

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()

    @Column(name = "version", nullable = false)
    var version: Long = 0

    @Column(name = "reversal_of")
    var reversalOf: UUID? = null

    /**
     * ADR-0252 synthetic-origin taint (V26). Explicit @Column name on purpose: only six services
     * set a camel-case physical naming strategy and this is not one of them, so the convention
     * here is to spell every column out (`check-entity-column-names.py`).
     */
    @Column(name = "synthetic", nullable = false)
    var synthetic: Boolean = false
}

data class JournalEntryEntityId(val id: UUID = UUID.randomUUID(), val entryDate: LocalDate = LocalDate.EPOCH) :
    Serializable

@Entity
@Table(name = "journal_lines")
class JournalLineEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID.randomUUID()

    @Column(name = "journal_id", nullable = false)
    var journalId: UUID = UUID.randomUUID()

    @Column(name = "gl_account_id", nullable = false)
    var glAccountId: UUID = UUID.randomUUID()

    @Column(name = "side", nullable = false)
    var side: String = "D"

    @Column(name = "amount", nullable = false)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency_code", nullable = false)
    var currencyCode: String = "CZK"

    @Column(name = "fx_rate")
    var fxRate: BigDecimal? = null

    @Column(name = "base_amount", nullable = false)
    var baseAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "base_currency", nullable = false)
    var baseCurrency: String = "CZK"

    @Column(name = "sequence", nullable = false)
    var sequence: Int = 0

    /** Sub-ledger dimension; non-null only on deposit-control legs (ADR-0039 Phase B). */
    @Column(name = "sub_account_id")
    var subAccountId: UUID? = null
}

@Entity
@Table(name = "gl_accounts")
class GlAccountEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = UUID.randomUUID()

    @Column(name = "code", nullable = false)
    var code: String = ""

    @Column(name = "name", nullable = false)
    var name: String = ""

    @Column(name = "type", nullable = false)
    var type: String = "ASSET"

    @Column(name = "currency_code", nullable = false)
    var currencyCode: String = "CZK"

    @Column(name = "parent_id")
    var parentId: UUID? = null

    @Column(name = "is_leaf", nullable = false)
    var isLeaf: Boolean = true

    @Column(name = "is_enabled", nullable = false)
    var isEnabled: Boolean = true

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

@Entity
@Table(name = "ledger_idempotency")
class LedgerIdempotencyEntity : PanacheEntityBase {
    @Id
    @Column(name = "idempotency_key")
    var idempotencyKey: String = ""

    @Column(name = "journal_id", nullable = false)
    var journalId: UUID = UUID.randomUUID()

    @Column(name = "journal_entry_date", nullable = false)
    var journalEntryDate: LocalDate = LocalDate.EPOCH

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
