// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.application.port.out

import java.math.BigDecimal
import java.util.UUID

/**
 * Cover (krytí) operations on the source currency pocket in balance-service.
 *
 * The payment saga reserves (holds) the settlement amount as the fail-fast, overdraft-aware overspend
 * gate before the ledger posting, and releases the hold on compensation. The booked balance itself is
 * **not** moved here: under ADR-0039 Phase D-2 the ledger is the golden source and balance-service
 * projects the booked movement (and releases the matching hold) from the ledger event — so the saga no
 * longer debits/credits balance directly. Reservations are overdraft-aware; currencies are never netted.
 */
interface BalanceCoverPort {
    /** Place a reservation on the pocket; returns the hold id used to release it later. */
    suspend fun placeHold(
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        referenceId: String,
        ttlSeconds: Long,
    ): UUID

    /** Release a previously placed reservation. Idempotent enough for compensation best-effort. */
    suspend fun releaseHold(holdId: UUID)
}
