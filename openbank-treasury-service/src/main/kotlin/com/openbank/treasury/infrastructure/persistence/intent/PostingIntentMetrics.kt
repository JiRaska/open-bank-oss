// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.intent

/** #13883 metric names, shared by the writer and the boot-time registration. */
object PostingIntentMetrics {
    /**
     * Journals applied after their intent was closed. Alerted on by
     * `TreasuryPostingIntentJournalAfterClose` with `increase()`, which cannot see a series that is
     * BORN at 1, so it is registered at 0 at boot (PostingIntentReconcileScheduler), never lazily.
     */
    const val APPLIED_AFTER_CLOSE = "openbank.treasury.posting_intents.applied_after_close"
}
