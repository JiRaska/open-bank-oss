// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.interest.application.port.out.LedgerPostingPort
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

/**
 * Binds [LedgerPostingPort] to `openbank-ledger-service` over REST (ADR-0033 §D): builds the
 * balanced capitalization journal with [CapitalizationJournalFactory] and posts it through
 * [LedgerCallGuard].
 *
 * Bound unconditionally rather than behind a `backend=none` no-op default (lending's pattern): the
 * ledger post is what makes a capitalization economically real, so a silent no-op binding would
 * reproduce the very defect this closes — recording the withholding liability, remitting cash for
 * it, and never crediting the customer. Interest-service already carries a hard REST dependency on
 * transaction-service for the remittance leg, so there is no offline-build story to protect; the
 * rest-client is lazy and the service still boots with the ledger unreachable.
 */
@ApplicationScoped
class RestLedgerPostingAdapter(private val guard: LedgerCallGuard, private val config: InterestLedgerConfig) :
    LedgerPostingPort {

    override fun post(posting: CapitalizationPosting): Uni<Unit> =
        guard.postJournal(CapitalizationJournalFactory.buildRequest(posting, config)).replaceWith(Unit)
}
