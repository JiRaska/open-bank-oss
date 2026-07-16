// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Outcome of one scheduled sub-ledger tie-out run (ADR-0039 Phase B).
 *
 * [BREAK] and [ERROR] are deliberately distinct: a break is a *confirmed* integrity incident
 * (GL net ≠ sub-ledger net), while an error means at least one control account could not be
 * checked at all — the day's control is missing, which is its own incident class (issue #855).
 */
enum class TieOutRunStatus { OK, BREAK, ERROR }

/** Durable record of one tie-out run; one row per run, persisted whatever the outcome. */
data class TieOutRunRecord(
    val id: UUID,
    val asOf: LocalDate,
    val runAt: Instant,
    val status: TieOutRunStatus,
    val accountsChecked: Int,
    val breaks: Int,
    val errors: Int,
)
