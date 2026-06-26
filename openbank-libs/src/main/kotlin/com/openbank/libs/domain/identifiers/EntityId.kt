// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.identifiers

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.util.UUID

/**
 * Marker for all typesafe entity identifiers. Each ID is a thin wrapper around a UUID with:
 *   - Jackson: `@JsonValue` + `@JsonCreator` make the JSON form a bare string, not an object.
 *   - Equality / hashCode from the underlying data class.
 *   - Companion `random()` and `of(String)` factories for ergonomics.
 *
 * JPA mapping: use the matching [com.openbank.libs.domain.identifiers.IdConverters].
 */
sealed interface EntityId {
    val value: UUID
}

private fun parseOrThrow(s: String, type: String): UUID = try {
    UUID.fromString(s)
} catch (ex: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid $type: '$s' is not a UUID", ex)
}

data class AccountId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = AccountId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = AccountId(parseOrThrow(s, "AccountId"))
    }
}

data class TransactionId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = TransactionId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = TransactionId(parseOrThrow(s, "TransactionId"))
    }
}

data class PartyId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = PartyId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = PartyId(parseOrThrow(s, "PartyId"))
    }
}

data class CardId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = CardId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = CardId(parseOrThrow(s, "CardId"))
    }
}

data class DisputeId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = DisputeId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = DisputeId(parseOrThrow(s, "DisputeId"))
    }
}

data class OrderId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = OrderId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = OrderId(parseOrThrow(s, "OrderId"))
    }
}

data class ConsentId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = ConsentId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = ConsentId(parseOrThrow(s, "ConsentId"))
    }
}

data class PaymentId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = PaymentId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = PaymentId(parseOrThrow(s, "PaymentId"))
    }
}

data class CaseId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = CaseId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = CaseId(parseOrThrow(s, "CaseId"))
    }
}
