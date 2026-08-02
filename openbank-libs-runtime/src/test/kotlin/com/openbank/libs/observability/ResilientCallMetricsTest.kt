// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.junit.jupiter.api.Test

/**
 * #3267's third ask: *breaker open* and *call failed* must be separable without log spelunking.
 * They mean opposite things — one is the dependency misbehaving, the other is this service
 * protecting itself — so a dashboard that cannot tell them apart reports a flat plateau of
 * "sanctions failing" while the dependency may already have recovered.
 */
class ResilientCallMetricsTest {

    private fun metrics(registry: SimpleMeterRegistry?): ResilientCallMetrics {
        val instance = mockk<Instance<io.micrometer.core.instrument.MeterRegistry>>()
        every { instance.isResolvable } returns (registry != null)
        if (registry != null) every { instance.get() } returns registry
        return ResilientCallMetrics().also { it.registryInstance = instance }
    }

    @Test
    fun `a breaker rejection and a transport fault are counted separately`() {
        val registry = SimpleMeterRegistry()
        val m = metrics(registry)

        m.recordFailure("sanctions", IllegalStateException("connection refused"))
        m.recordFailure("sanctions", IllegalStateException("500"))
        m.recordFailure("sanctions", CircuitBreakerOpenException("open"))

        fun count(outcome: String) = registry
            .counter("openbank.resilient.call.failures", "adapter", "sanctions", "outcome", outcome)
            .count()

        assertThat(count(ResilientCallMetrics.OUTCOME_CALL_FAILED)).isEqualTo(2.0)
        assertThat(count(ResilientCallMetrics.OUTCOME_BREAKER_OPEN)).isEqualTo(1.0)
    }

    @Test
    fun `it is a silent no-op when the service has no meter registry`() {
        // libs-runtime is consumed by services with no quarkus-micrometer on the classpath; the
        // bean must load and do nothing rather than fail their boot.
        metrics(null).recordFailure("sanctions", RuntimeException("boom"))
    }
}
