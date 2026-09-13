// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.out

import java.util.UUID

/**
 * Asks the ledger whether this settlement's booking actually reached the general ledger
 * (issue #6410).
 *
 * ## Why the compensation needs this at all
 *
 * `bookToLedger` is not atomic: it posts the journal and *then* writes
 * [com.openbank.settlement.domain.model.SettlementStatus.BOOKED]. Either half can fail on its own,
 * so when the activity throws there is no way to tell from settlement-service's own state whether
 * a journal was posted. `reverseBookToLedger` used to assume the worst and unconditionally record
 * `LEDGER_REVERSAL_UNSUPPORTED`, which would page an accountant to correct a GL entry that, in the
 * ordinary "ledger refused the posting" case, does not exist.
 *
 * ## Why a lookup is possible at all
 *
 * The KDoc that shipped with the unimplemented reversal listed "no journal id is retained" as one
 * of three blockers. That one does not hold: `SettlementJournalFactory` posts with
 * `transactionId = settlementId`, and ledger-service exposes
 * `GET /api/v1/journals/transaction/{transactionId}`. The settlement id **is** the handle. (The
 * other two blockers — `ledger.reverse` being a four-eyes verb, and an ATTESTED period refusing a
 * reversal outright — are real, and are why this port only *reads*.)
 */
interface LedgerJournalLookupPort {
    /**
     * How many journal entries the ledger holds for [settlementId] as its `transactionId`.
     *
     * Returns `0` for a settlement whose booking never posted. **Throws** when the question could
     * not be answered — an unreachable or erroring ledger must never be reported as `0`, which
     * would be a clean-GL claim nobody established.
     */
    suspend fun countJournalsForSettlement(settlementId: UUID): Int
}
