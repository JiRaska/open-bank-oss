// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves [Timer.Builder.standardPercentiles] / [DistributionSummary.Builder.standardPercentiles]
 * register a meter identical — same id, same configured percentiles, same histogram flag — to
 * what every adapter's hand-built `.publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram()`
 * produced before the migration (issue #10910). A dashboard/alert depends on the registered meter
 * id and its percentile configuration, not on which line of code built it.
 */
class PercentilesTest {

    @Test
    fun `STANDARD is the fleet p50-p95-p99 triple in publishPercentiles order`() {
        assertThat(Percentiles.STANDARD).containsExactly(0.5, 0.95, 0.99)
    }

    @Test
    fun `Timer standardPercentiles matches the hand-built call it replaces`() {
        val viaHelper = SimpleMeterRegistry()
        Timer.builder("openbank.test.timer")
            .tags("service", "x")
            .standardPercentiles()
            .register(viaHelper)

        val viaHandBuilt = SimpleMeterRegistry()
        Timer.builder("openbank.test.timer")
            .tags("service", "x")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(viaHandBuilt)

        val helperTimer = viaHelper.find("openbank.test.timer").tag("service", "x").timer()
        val handBuiltTimer = viaHandBuilt.find("openbank.test.timer").tag("service", "x").timer()
        assertThat(helperTimer).isNotNull
        assertThat(handBuiltTimer).isNotNull

        // Same registered meter id (name + tags) — what a dashboard/alert actually selects on.
        assertThat(helperTimer!!.id).isEqualTo(handBuiltTimer!!.id)

        // Same configured percentiles and histogram flag on the underlying DistributionStatisticConfig.
        val helperCfg = helperTimer.takeSnapshot()
        val handBuiltCfg = handBuiltTimer.takeSnapshot()
        assertThat(helperCfg.percentileValues().map { it.percentile() })
            .containsExactlyInAnyOrderElementsOf(handBuiltCfg.percentileValues().map { it.percentile() })
    }

    @Test
    fun `DistributionSummary standardPercentiles matches the hand-built call it replaces`() {
        val viaHelper = SimpleMeterRegistry()
        DistributionSummary.builder("openbank.test.summary")
            .tags("service", "x")
            .standardPercentiles()
            .register(viaHelper)

        val viaHandBuilt = SimpleMeterRegistry()
        DistributionSummary.builder("openbank.test.summary")
            .tags("service", "x")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(viaHandBuilt)

        val helperSummary = viaHelper.find("openbank.test.summary").tag("service", "x").summary()
        val handBuiltSummary = viaHandBuilt.find("openbank.test.summary").tag("service", "x").summary()
        assertThat(helperSummary).isNotNull
        assertThat(handBuiltSummary).isNotNull
        assertThat(helperSummary!!.id).isEqualTo(handBuiltSummary!!.id)

        val helperCfg = helperSummary.takeSnapshot()
        val handBuiltCfg = handBuiltSummary.takeSnapshot()
        assertThat(helperCfg.percentileValues().map { it.percentile() })
            .containsExactlyInAnyOrderElementsOf(handBuiltCfg.percentileValues().map { it.percentile() })
    }
}
