// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.observability

import com.openbank.pensionfund.domain.model.OrderType
import com.openbank.pensionfund.infrastructure.rest.ExceptionMappers
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.persistence.OptimisticLockException
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.StaleObjectStateException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeUnit

class MicrometerPensionFundMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerPensionFundMetrics(registry)

    @Test
    fun `a non-positive amount never moves a money counter`() {
        metrics.settledAmount("CZ0000000301", OrderType.REDEEM, BigDecimal.ZERO, "CZK")
        metrics.settledAmount("CZ0000000301", OrderType.REDEEM, BigDecimal("-5"), "CZK")
        metrics.settledAmount("CZ0000000301", OrderType.REDEEM, BigDecimal("12.5"), "CZK")

        assertThat(registry.find("openbank.pension_fund.settled.amount").counter()!!.count()).isEqualTo(12.5)
    }

    @Test
    fun `a publication before the valuation day ended records zero lag, never a negative one`() {
        metrics.navPublicationLag("CZ0000000301", Duration.ofHours(-2))
        metrics.navPublicationLag("CZ0000000301", Duration.ofHours(3))

        val timer = registry.find("openbank.pension_fund.nav.publication.lag").timer()!!
        assertThat(timer.count()).isEqualTo(2)
        assertThat(timer.totalTime(TimeUnit.HOURS)).isEqualTo(3.0)
    }

    @Test
    fun `both optimistic-lock mappers answer 409 and count one conflict on the unit holding`() {
        val mappers = ExceptionMappers(metrics)
        assertThat(mappers.staleHolding(OptimisticLockException("stale")).status).isEqualTo(409)
        assertThat(mappers.staleHoldingHibernate(StaleObjectStateException("UnitHolding", 1)).status).isEqualTo(409)

        assertThat(
            registry.find("openbank.pension_fund.optimistic_lock.conflicts").tag("aggregate", "unit_holding")
                .counter()!!.count(),
        ).isEqualTo(2.0)
    }
}
