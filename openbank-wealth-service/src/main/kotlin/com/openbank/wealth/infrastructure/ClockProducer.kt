// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** One injectable clock, so a test can fix time instead of sleeping (ADR-0207 on time authority). */
@ApplicationScoped
class ClockProducer {
    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
