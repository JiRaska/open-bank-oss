// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Wall-clock UTC for timestamps, which is the fleet convention and the right answer for them
 * (ADR-0207). The **accounting** day is a different question and is not derived from this bean
 * directly: `SpendWindow` wraps it in an `AccountingClock`, so a limit window follows the bank's day
 * rather than UTC midnight.
 */
@ApplicationScoped
class ClockProducer {
    @Produces
    @Dependent
    fun clock(): Clock = Clock.systemUTC()
}
