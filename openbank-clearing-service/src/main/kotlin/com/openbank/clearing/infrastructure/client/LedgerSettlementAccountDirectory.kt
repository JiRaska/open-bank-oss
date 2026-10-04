// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.client

import com.openbank.clearing.application.port.out.SettlementAccountDirectory
import jakarta.enterprise.context.ApplicationScoped

/** The settleable currencies are exactly those [NetSettlementJournalFactory] can post (#11974). */
@ApplicationScoped
class LedgerSettlementAccountDirectory : SettlementAccountDirectory {
    override fun settleableCurrencies(): Set<String> = NetSettlementJournalFactory.settleableCurrencies
}
