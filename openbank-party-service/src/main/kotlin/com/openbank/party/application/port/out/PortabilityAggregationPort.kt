// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import com.openbank.party.domain.model.PortabilityAccount
import com.openbank.party.domain.model.PortabilityCard
import java.util.UUID

/**
 * Outbound port for the GDPR Art. 20 portability aggregation (ADR-0204): fetches the subject's
 * accounts (with transaction history) and card metadata from the owning services. Best-effort
 * like [GdprAggregationPort] — a downstream outage degrades to an empty slice, never blocks the
 * subject's request (the DPO follows up from the log); a 401/403 still fails hard, because a
 * refused read is indistinguishable from "no data" only at the cost of silently shipping an
 * empty export as if it were complete.
 */
interface PortabilityAggregationPort {
    suspend fun fetchAccountsWithTransactions(partyId: UUID): List<PortabilityAccount>
    suspend fun fetchCards(partyId: UUID): List<PortabilityCard>
}
