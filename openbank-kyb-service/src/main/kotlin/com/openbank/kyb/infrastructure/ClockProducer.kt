// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyb.infrastructure

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import java.time.Clock

@ApplicationScoped
class ClockProducer {
    @Produces
    @Dependent
    fun clock(): Clock = Clock.systemUTC()
}
