// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure

import com.openbank.kyb.application.port.out.KybMetricsPort
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class MicrometerKybMetrics(private val registry: MeterRegistry) : KybMetricsPort {

    override fun caseStarted(scheme: String, outcome: String) {
        registry.counter("openbank.kyb.cases.started", "scheme", scheme, "outcome", outcome).increment()
    }

    override fun registryLookup(source: String, outcome: String) {
        registry.counter("openbank.kyb.registry.lookups", "source", source, "outcome", outcome).increment()
    }

    override fun timerArmingFailed(state: String) {
        registry.counter("openbank.kyb.timers.arming_failed", "state", state).increment()
    }
}
