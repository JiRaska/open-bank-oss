// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.port.out

import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import java.time.LocalDate

/** Evidence that the billing cycle ran for an accounting day (ADR-0342 D1). */
interface CloseJobRunPort {
    /** Upsert on `(businessDate, job)`: a re-run for the same day replaces the counts. */
    fun record(run: CloseJobRun): Uni<Unit>

    /** Every run with `businessDate` in `[from, to]`. */
    fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>>
}

/**
 * Where a cycle's chargeable fees stand with the ledger. Billing posts a fee through its outbox, so
 * the cycle finishing is not the journals being booked: the day is complete only once no fee of the
 * cycle is still [pending].
 *
 * @property pending fees whose outbox row has not reached the ledger yet (`PENDING`)
 * @property posted fees the ledger booked (`POSTED`, or later reversed: the charge journal exists)
 * @property failed fees that will not be booked by this cycle (`FAILED`: exhausted retries, or the
 *   ledger refused the day with `ACCOUNTING_DAY_CLOSED`)
 */
data class CycleFeePostings(val pending: Int, val posted: Int, val failed: Int)

/** Read side of [CycleFeePostings]. */
interface CycleFeePostingsPort {
    fun postingsFor(cycleId: String): Uni<CycleFeePostings>
}

/**
 * Authorisation of a re-bill outbox row (runbook 0033). A row carrying `rebillOf` settles the ORIGINAL
 * fee with a journal posted under a new key, so it must have been approved: a `billing_fee_rebill` row
 * binding exactly that new key to that original, and the original must still be unbooked.
 */
fun interface FeeRebillPort {
    suspend fun isAuthorised(rebillKey: String, originalKey: String): Boolean
}

/** Everything the day-close reporter reads: the cycle run evidence and the cycle's fee postings. */
interface BillingCloseEvidencePort :
    CloseJobRunPort,
    CycleFeePostingsPort
