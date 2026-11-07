// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The PUBLISHED product-catalog revision an account was opened under (ADR-0341 D1, ADR-0352 phase 3).
 *
 * Taken once, at opening, and never edited: a change of terms is a new revision plus an explicit
 * re-pin, which is its own auditable event. The database enforces that (V33 trigger).
 *
 * [irRevisionHash] / [irVersion] are present only when the revision carries signed product IR
 * (ADR-0352 §4). A revision that is a v1 compatibility projection carries none, and pinning it is
 * still a pin: its [revisionId] and [contentHash] name immutable content. Both stay null together.
 */
data class ProductRevisionPin(
    val offeringId: UUID,
    /** The v1 product code the account was opened against; kept for readers that only know codes. */
    val productCode: String,
    val revisionId: UUID,
    val revisionNumber: Long,
    /** Catalog `contentHash` of the revision, when the catalog stamped one. */
    val contentHash: String?,
    /** Canonical IR `revisionHash` the publish path signed (ADR-0352 §4); null for a revision without IR. */
    val irRevisionHash: String?,
    val irVersion: Int?,
    /** From the injected clock at opening (ADR-0100). */
    val pinnedAt: Instant,
) {
    init {
        require(revisionNumber >= 1) { "revisionNumber must be >= 1" }
        require(productCode.isNotBlank()) { "productCode must not be blank" }
        require((irRevisionHash == null) == (irVersion == null)) { "irRevisionHash and irVersion travel together" }
        contentHash?.let { require(HASH.matches(it)) { "contentHash must be 64 lower-case hex" } }
        irRevisionHash?.let { require(HASH.matches(it)) { "irRevisionHash must be 64 lower-case hex" } }
    }

    private companion object {
        val HASH = Regex("^[0-9a-f]{64}$")
    }
}
