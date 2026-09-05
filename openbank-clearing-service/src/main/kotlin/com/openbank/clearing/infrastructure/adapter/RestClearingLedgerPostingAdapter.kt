// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.adapter

import com.openbank.clearing.application.port.out.ClearingLedgerPostingPort
import com.openbank.clearing.application.port.out.NetSettlementPosting
import com.openbank.clearing.infrastructure.client.ClearingLedgerRestClient
import com.openbank.clearing.infrastructure.client.NetSettlementJournalFactory
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * [ClearingLedgerPostingPort] over the ledger-service journal API (ADR-0281). The journal shape
 * (balanced DEBIT cash-clearing / CREDIT scheme-settlement, per-currency GLs) is
 * [NetSettlementJournalFactory]'s; this adapter only guards the wire call. Idempotency lives on
 * the posting's deterministic key, enforced by ledger-service — a retry collapses onto the one
 * booked journal.
 */
@ApplicationScoped
class RestClearingLedgerPostingAdapter(@param:RestClient private val client: ClearingLedgerRestClient) :
    ClearingLedgerPostingPort {

    override fun postNetSettlement(posting: NetSettlementPosting): Uni<Unit> =
        client.postJournal(NetSettlementJournalFactory.build(posting)).replaceWith(Unit)
}
