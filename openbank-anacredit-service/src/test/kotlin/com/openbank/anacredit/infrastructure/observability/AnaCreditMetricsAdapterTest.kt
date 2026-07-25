// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.observability

import com.openbank.anacredit.application.port.out.LoanStageEventOutcome
import com.openbank.anacredit.domain.model.InstrumentType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class AnaCreditMetricsAdapterTest {

    private val registry = SimpleMeterRegistry()
    private val adapter = AnaCreditMetricsAdapter(registry)

    @Test
    fun `exposure intake increments an instrument-type and currency tagged counter`() {
        adapter.exposureRegistered(InstrumentType.OVERDRAFT, "EUR", defaulted = false)
        adapter.exposureRegistered(InstrumentType.OVERDRAFT, "EUR", defaulted = false)
        adapter.exposureRegistered(InstrumentType.LOAN, "CZK", defaulted = true)

        assertThat(
            registry.get("openbank.anacredit.exposures.registered")
                .tag("service", "anacredit")
                .tag("instrument_type", "OVERDRAFT")
                .tag("currency", "EUR")
                .tag("defaulted", "false")
                .counter().count(),
        ).isEqualTo(2.0)
        assertThat(
            registry.get("openbank.anacredit.exposures.registered")
                .tag("instrument_type", "LOAN")
                .tag("currency", "CZK")
                .tag("defaulted", "true")
                .counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a rendered return publishes its duration, row count and exclusion count`() {
        adapter.returnBuilt(recordCount = 7, exclusionCount = 2, duration = Duration.ofMillis(120))

        assertThat(registry.get("openbank.anacredit.return.build.duration").timer().count()).isEqualTo(1L)
        val records = registry.get("openbank.anacredit.return.records").summary()
        assertThat(records.count()).isEqualTo(1L)
        assertThat(records.totalAmount()).isEqualTo(7.0)
        val exclusions = registry.get("openbank.anacredit.return.exclusions").summary()
        assertThat(exclusions.totalAmount()).isEqualTo(2.0)
    }

    @Test
    fun `each loan-stage outcome gets its own lower-cased tag value`() {
        adapter.loanStageEvent(LoanStageEventOutcome.APPLIED)
        adapter.loanStageEvent(LoanStageEventOutcome.PARSE_ERROR)
        adapter.loanStageEvent(LoanStageEventOutcome.PARSE_ERROR)

        assertThat(counter("applied")).isEqualTo(1.0)
        assertThat(counter("parse_error")).isEqualTo(2.0)
    }

    @Test
    fun `is a silent no-op when no meter registry is resolvable`() {
        // Slim slices without a Prometheus registry must not crash the feed.
        val noRegistry = AnaCreditMetricsAdapter(null)

        noRegistry.exposureRegistered(InstrumentType.LOAN, "EUR", defaulted = false)
        noRegistry.returnBuilt(1, 0, Duration.ZERO)
        noRegistry.loanStageEvent(LoanStageEventOutcome.APPLIED)
    }

    private fun counter(outcome: String): Double = registry.get("openbank.anacredit.loan_stage.events")
        .tag("service", "anacredit")
        .tag("outcome", outcome)
        .counter().count()
}
