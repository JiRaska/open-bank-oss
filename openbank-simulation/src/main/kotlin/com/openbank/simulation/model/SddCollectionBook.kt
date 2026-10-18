// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.sdd.domain.model.SddMandate
import java.math.BigDecimal
import java.util.UUID

/** One collection attempt against a mandate; production has no collection aggregate (the debit
 * goes straight through the transaction path), so the scenario records the attempt here — the
 * invariant-side view of the money movement. */
enum class SddCollectionStatus { SETTLED, REJECTED, REFUSED, FAILED, REVERSED }

data class SddCollectionRecord(
    val id: UUID,
    val mandateId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: SddCollectionStatus,
    val reasonCode: String? = null,
    val debitJournalId: UUID? = null,
    val reversed: Boolean = false,
    val refundJournalId: UUID? = null,
)

/**
 * Money-path SEPA Direct Debit adoption: the invariant-side book for
 * [com.openbank.simulation.scenario.SddCollectionScenario], mirroring ClearingCycleBook's role.
 * Holds the REAL `SddMandate` aggregates the scenario drives through the real
 * `MandateLifecycle` state machine, plus the per-collection record the money-side invariants
 * assert over (one debit journal per settled collection, none for rejected/refused/failed,
 * reversal for a conflicted one, one refund journal per refunded).
 */
class SddCollectionBook {

    val mandates = mutableListOf<SddMandate>()
    val collections = mutableListOf<SddCollectionRecord>()

    fun recordMandate(mandate: SddMandate) {
        mandates.add(mandate)
    }

    fun replaceMandate(updated: SddMandate) {
        val index = mandates.indexOfFirst { it.id == updated.id }
        mandates[index] = updated
    }

    fun recordCollection(record: SddCollectionRecord) {
        collections.add(record)
    }

    fun replaceCollection(updated: SddCollectionRecord) {
        val index = collections.indexOfFirst { it.id == updated.id }
        collections[index] = updated
    }

    fun collectionsOf(mandateId: UUID): List<SddCollectionRecord> = collections.filter { it.mandateId == mandateId }
}
