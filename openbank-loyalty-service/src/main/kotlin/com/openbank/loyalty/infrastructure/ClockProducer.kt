// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** One injectable clock, so a test can freeze time instead of sleeping through an expiry. */
@ApplicationScoped
class ClockProducer {
    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
