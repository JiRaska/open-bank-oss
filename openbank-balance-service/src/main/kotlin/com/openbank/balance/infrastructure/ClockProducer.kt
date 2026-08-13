// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure

import com.openbank.libs.domain.calendar.AccountingClock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import java.time.Clock

@ApplicationScoped
class ClockProducer {

    @Produces
    @Dependent
    fun clock(): Clock = Clock.systemUTC()

    /**
     * The accounting day authority (ADR-0207 D1). The UTC bean above stays correct for *timestamps*;
     * "which accounting day is it" is a domain question with the bank's own zone and cutoff, and
     * balance-service must answer it identically to the ledger it projects — the value-date cut
     * (`entry_date <= today`) is compared against ledger-produced dates, so a two-hour zone
     * disagreement would move a credit into or out of spendability for two hours a day.
     * Constructed here, in infrastructure, which is where a clock is legitimately built.
     */
    @Produces
    @Dependent
    fun accountingClock(clock: Clock): AccountingClock = AccountingClock.bank(clock)
}
