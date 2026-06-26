// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.identifiers

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.util.UUID

/**
 * Typesafe identifiers for the lending bounded context (ADR-0028). These live in their own file
 * but still implement the [EntityId] sealed interface — Kotlin permits sealed implementations across
 * files of the same package and compilation module, which keeps the shared [EntityId] file small.
 */

private fun parseLendingId(s: String, type: String): UUID = try {
    UUID.fromString(s)
} catch (ex: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid $type: '$s' is not a UUID", ex)
}

/** A credit application before it becomes a booked loan (origination funnel). */
data class LoanApplicationId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = LoanApplicationId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = LoanApplicationId(parseLendingId(s, "LoanApplicationId"))
    }
}

/** A booked loan account (the servicing aggregate). */
data class LoanId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = LoanId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = LoanId(parseLendingId(s, "LoanId"))
    }
}

/** A collateral item pledged against one or more loans. */
data class CollateralId(@get:JsonValue override val value: UUID) : EntityId {
    companion object {
        @JvmStatic fun random() = CollateralId(Ids.newId())

        @JvmStatic @JsonCreator
        fun of(s: String) = CollateralId(parseLendingId(s, "CollateralId"))
    }
}
