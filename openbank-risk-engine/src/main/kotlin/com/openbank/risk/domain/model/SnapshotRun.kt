// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** ADR-0313 D13: every run says whether it was computed over synthetic or production data. */
enum class Provenance(val wire: String) {
    SYNTHETIC("synthetic"),
    PRODUCTION("production"),
    ;

    companion object {
        fun parse(value: String): Provenance = entries.firstOrNull { it.wire == value.trim().lowercase() }
            ?: throw IllegalArgumentException(
                "openbank.risk.provenance must be 'synthetic' or 'production', was '$value'",
            )
    }
}

/**
 * The manifest of one snapshot run (ADR-0314 D2).
 *
 * Natural key `(asOf, inputHash)`: the same ledger at the same as-of is the same knowledge and
 * yields the existing run; a different hash at the same as-of is newer knowledge and a new run,
 * told apart from the earlier one by [recordedAt].
 */
data class SnapshotRun(
    val id: UUID,
    val asOf: LocalDate,
    val recordedAt: Instant,
    val inputHash: String,
    val provenance: Provenance,
    val status: TieOutStatus,
    val positionCount: Int,
    val mismatches: List<TieOutMismatch>,
)
