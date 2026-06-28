// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import io.temporal.activity.ActivityInterface
import java.util.UUID

/**
 * Temporal activities mirroring the side-effecting steps of [PaymentSagaOrchestrator] (ADR-0120 P1).
 *
 * Each activity wraps exactly one of the orchestrator's port interactions so the workflow can drive
 * the same cover-hold / ledger-posting protocol with Temporal's durable retries and compensation.
 */
@ActivityInterface
interface PaymentActivities {
    /**
     * Place the overdraft-aware cover hold on the source pocket. Returns the hold id.
     * Incoming credits (no source account) skip the hold and return the sentinel [SENTINEL_HOLD].
     */
    fun placeHold(transactionId: UUID): UUID

    /** Post the ledger journal for the payment. Returns the journal id. */
    fun postJournal(transactionId: UUID): UUID

    /** Best-effort release of a previously placed cover hold (compensation). */
    fun releaseHold(holdId: UUID)

    /** Best-effort reversal of a previously posted ledger journal (compensation). */
    fun reverseJournal(journalId: UUID)

    companion object {
        /** Returned by [placeHold] when there is no source account, so no hold was placed. */
        val SENTINEL_HOLD: UUID = UUID(0L, 0L)
    }
}
