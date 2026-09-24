// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import java.math.BigDecimal
import java.util.UUID

/** Where a position came from — which decides what the tie-out can say about it. */
enum class PositionKind {
    /** A customer account on a deposit-control GL account (contract level). */
    SUB_LEDGER,

    /** A GL account the ledger exposes no finer breakdown for (GL level). */
    GL_ACCOUNT,
}

/**
 * One contract-level (or, where nothing finer exists, GL-level) position of a snapshot.
 *
 * [amount] is in the TRIAL-BALANCE sign convention (debit − credit) for every kind, so positions
 * can be summed against the trial balance directly. A sub-ledger balance arrives credit-normal
 * from the ledger and is flipped on the way in; storing two conventions in one column is how a
 * tie-out ends up comparing a liability against its own negation.
 *
 * [glAccountCode] is null only for a sub-ledger balance whose currency has no single
 * deposit-control account in the trial balance — an UNMAPPED position, which the tie-out always
 * reports.
 */
data class Position(
    val kind: PositionKind,
    val glAccountCode: String?,
    val glAccountType: String?,
    val currency: String,
    val subAccountId: UUID?,
    val amount: BigDecimal,
)
