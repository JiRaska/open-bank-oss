// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.config

import com.openbank.libs.domain.calendar.AccountingClock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import java.time.Clock

@ApplicationScoped
class ClockProducer {
    /** Wall-clock time, UTC — for timestamps. */
    @Produces
    @Dependent
    fun clock(): Clock = Clock.systemUTC()

    /**
     * The accounting-day authority (ADR-0207 D1). A §38d deadline is an accounting date, not a
     * wall-clock instant: "has this month ended" must not depend on which clock object is asked,
     * which is the defect ADR-0207 removed from ledger-service.
     */
    @Produces
    @Dependent
    fun accountingClock(clock: Clock): AccountingClock = AccountingClock.bank(clock)
}
