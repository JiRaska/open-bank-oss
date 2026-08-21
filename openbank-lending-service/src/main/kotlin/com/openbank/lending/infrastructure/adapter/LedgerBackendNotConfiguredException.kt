// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.PostingKind

/**
 * Raised by the offline `@Default` no-op adapters instead of reporting a success they did not
 * achieve (#6057).
 *
 * A no-op, disabled or skipped outcome must never share its signal with a real one. The previous
 * `NoOpLedgerPostingPort` returned `Uni<Unit>` — the *same* value the real
 * [RestLedgerPostingAdapter] returns after ledger-service accepts the journal — and logged the
 * discard at `debug`, below the shipped level. Nothing anywhere could tell a posted journal from a
 * dropped one, which is how 44 active loans (6.6M CZK principal, 1056 installments, 88 provisioning
 * cycles) accumulated against a general ledger holding zero lines on every lending GL account.
 *
 * Being a distinct *failure* rather than a distinct success value is deliberate: every
 * `LedgerPostingPort` call site is a reactive chain that already propagates failure, so the
 * refusal automatically prevents the downstream state change (marking an installment accrued,
 * emitting `loan.disbursed`) from claiming work the ledger never recorded.
 */
class LedgerBackendNotConfiguredException(kind: PostingKind, reference: String) :
    IllegalStateException(
        "lending.ledger.backend is not 'rest': refusing to report $kind posting '$reference' as " +
            "recorded. No general-ledger journal was written. Build the image with " +
            "lending.ledger.backend=rest (a BUILD-time property — a runtime env var cannot " +
            "change it) to bind RestLedgerPostingAdapter.",
    )

/** Sibling of [LedgerBackendNotConfiguredException] for the borrower-facing credit/debit leg. */
class BorrowerCreditBackendNotConfiguredException(operation: String, reference: String) :
    IllegalStateException(
        "lending.borrower-credit.backend is not 'rest': refusing to report $operation '$reference' " +
            "as executed. The borrower was not paid. Build the image with " +
            "lending.borrower-credit.backend=rest (a BUILD-time property — a runtime env var " +
            "cannot change it) to bind BorrowerCreditClient.",
    )
