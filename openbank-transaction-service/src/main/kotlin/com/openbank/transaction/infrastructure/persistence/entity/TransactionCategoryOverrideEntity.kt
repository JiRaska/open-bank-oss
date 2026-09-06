// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * One customer's chosen category for everything they pay to one counterparty, on one account.
 *
 * See `V18__create_transaction_category_override.sql` for why the key is the counterparty rather
 * than the transaction, and why the scope is the account.
 */
@Entity
@Table(name = "transaction_category_override")
class TransactionCategoryOverrideEntity : PanacheEntityBase {
    @EmbeddedId
    var id: Key = Key()

    /**
     * A [com.openbank.libs.spend.SpendCategory] id.
     *
     * Stored as text rather than an enum so that retiring a category elsewhere leaves these rows
     * readable instead of failing the whole page load. The read path drops values it does not
     * recognise.
     */
    @Column(name = "category", nullable = false)
    var category: String = ""

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /** Composite primary key: the account the override belongs to, and who it is about. */
    @Embeddable
    class Key(
        @Column(name = "account_id")
        var accountId: UUID? = null,
        @Column(name = "counterparty_key")
        var counterpartyKey: String? = null,
    ) : Serializable {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Key && accountId == other.accountId && counterpartyKey == other.counterpartyKey)

        override fun hashCode(): Int = (accountId?.hashCode() ?: 0) * 31 + (counterpartyKey?.hashCode() ?: 0)

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
