// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.interest.application.port.out.CapitalizationPosting
import com.openbank.interest.application.port.out.LedgerPostingPort
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate

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
 *
 * The booking date is today's ledger business date ([CapitalizationJournalFactory.LEDGER_ZONE]),
 * read at post time, so a capitalization completed late books forward into the open day instead of
 * back into a closed one — see [CapitalizationJournalFactory.buildRequest].
 */
@ApplicationScoped
class RestLedgerPostingAdapter(private val guard: LedgerCallGuard, private val config: InterestLedgerConfig) :
    LedgerPostingPort {

    /** Overridable only so a test can pin "today"; production reads the system clock. */
    internal var clock: Clock = Clock.systemUTC()

    override fun post(posting: CapitalizationPosting): Uni<Unit> {
        val bookingDate = LocalDate.now(clock.withZone(CapitalizationJournalFactory.LEDGER_ZONE))
        return guard.postJournal(CapitalizationJournalFactory.buildRequest(posting, config, bookingDate))
            .replaceWith(Unit)
    }
}
