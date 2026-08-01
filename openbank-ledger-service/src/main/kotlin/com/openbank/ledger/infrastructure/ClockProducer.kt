// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure

import com.openbank.libs.domain.calendar.AccountingClock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import java.time.Clock

@ApplicationScoped
class ClockProducer {
    /**
     * Wall-clock time, UTC — correct for *timestamps* and unchanged by ADR-0207. This bean is
     * byte-identical to the one 44 other services produce and is deliberately left alone.
     */
    @Produces
    @Dependent
    fun clock(): Clock = Clock.systemUTC()

    /**
     * The accounting-day authority (ADR-0207 D1) — the only supported answer to "what accounting
     * day is it". Distinct from [clock] on purpose: an accounting date is a domain value with its
     * own calendar and cutoff, not a projection of wall-clock time. Deriving it from a wall clock
     * per caller is the defect ADR-0207 removes (this service ran BOTH regimes at once: a UTC
     * `Clock` bean here, and `Clock.system(Europe/Prague)` built inside `LedgerService` and
     * `YearCloseService`, which disagreed about the date for two hours a day, half the year).
     */
    @Produces
    @Dependent
    fun accountingClock(clock: Clock): AccountingClock = AccountingClock.bank(clock)
}
