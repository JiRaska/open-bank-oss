// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.LedgerJournalLookupPort
import com.openbank.settlement.infrastructure.client.LedgerRestClient
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Reads back what the general ledger actually holds for a settlement (issue #6410).
 *
 * Deliberately does **not** catch: a failed lookup must reach the caller as a failure, so
 * `SettlementActivitiesImpl.reverseBookToLedger` can record
 * [com.openbank.settlement.domain.model.SettlementStatus.LEDGER_STATE_UNKNOWN] rather than
 * silently degrade to "no journal found", which is the difference between "we checked" and "we
 * could not check".
 */
@ApplicationScoped
class LedgerJournalLookupAdapter(@RestClient private val ledgerClient: LedgerRestClient) : LedgerJournalLookupPort {

    override suspend fun countJournalsForSettlement(settlementId: UUID): Int =
        ledgerClient.getJournalsByTransaction(settlementId).subscribeAsCompletionStage().await().size
}
