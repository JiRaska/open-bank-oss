// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HistogramMeterFiltersTest {

    private val filter = HistogramMeterFilters().exemplarHistogramFilter()

    private fun timerId(name: String) = Meter.Id(name, Tags.empty(), null, null, Meter.Type.TIMER)

    @Test
    fun `enables percentile histogram buckets for the http server timer`() {
        val configured = filter.configure(timerId("http.server.requests"), DistributionStatisticConfig.DEFAULT)

        assertThat(configured!!.isPercentileHistogram).isTrue()
    }

    @Test
    fun `enables percentile histogram buckets for the domain payment processing timer`() {
        val configured =
            filter.configure(timerId("openbank.payment.processing.duration"), DistributionStatisticConfig.DEFAULT)

        assertThat(configured!!.isPercentileHistogram).isTrue()
    }

    @Test
    fun `leaves every other meter untouched`() {
        val original = DistributionStatisticConfig.DEFAULT

        val configured = filter.configure(timerId("openbank.outbox.backlog"), original)

        assertThat(configured).isSameAs(original)
        assertThat(configured!!.isPercentileHistogram).isNotEqualTo(true)
    }

    @Test
    fun `keeps caller overrides when merging the histogram config`() {
        val original = DistributionStatisticConfig.builder()
            .percentilePrecision(3)
            .build()
            .merge(DistributionStatisticConfig.DEFAULT)

        val configured = filter.configure(timerId("http.server.requests"), original)

        assertThat(configured!!.isPercentileHistogram).isTrue()
        assertThat(configured.percentilePrecision).isEqualTo(3)
    }
}
