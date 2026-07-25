// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.application

import com.openbank.anacredit.Fixtures
import com.openbank.anacredit.application.port.`in`.RegisterExposureCommand
import com.openbank.anacredit.application.port.out.CreditExposureRepository
import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.model.InstrumentType
import com.openbank.anacredit.infrastructure.observability.AnaCreditMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Drives the real [AnaCreditMetricsAdapter] over a [SimpleMeterRegistry] rather than a mock port, so
 * the assertions fail if [AnaCreditService] stops calling the meters — a metrics test that passes
 * without the instrumentation guards nothing.
 */
class AnaCreditServiceMetricsTest {

    private val exposures = mockk<CreditExposureRepository>()
    private val registry = SimpleMeterRegistry()
    private val service = AnaCreditService(exposures, AnaCreditMetricsAdapter(registry))

    @Test
    fun `registering an exposure increments the intake counter with the stored instrument's tags`(): Unit =
        runBlocking {
            coEvery { exposures.upsert(any()) } answers { firstArg() }

            service.register(command(instrumentType = InstrumentType.LOAN, currency = "CZK"))

            assertThat(
                registry.get("openbank.anacredit.exposures.registered")
                    .tag("service", "anacredit")
                    .tag("instrument_type", "LOAN")
                    .tag("currency", "CZK")
                    .tag("defaulted", "false")
                    .counter().count(),
            ).isEqualTo(1.0)
        }

    @Test
    fun `rendering a return publishes the row count and the exclusion count actually produced`(): Unit = runBlocking {
        // One reportable exposure (EUR 40 000 commitment, over the AnaCredit threshold) and one
        // dropped by the eligibility policy (a natural-person debtor is out of scope).
        coEvery { exposures.listAll() } returns listOf(
            Fixtures.exposure(instrumentId = "OD-0001"),
            Fixtures.exposure(
                instrumentId = "OD-0002",
                debtorId = "NP-JOE",
                debtorType = CounterpartyType.NATURAL_PERSON,
            ),
        )

        val report = service.build(LocalDate.parse("2026-06-30"))

        assertThat(report.records).hasSize(1)
        assertThat(report.exclusions).hasSize(1)
        assertThat(registry.get("openbank.anacredit.return.build.duration").timer().count()).isEqualTo(1L)
        assertThat(registry.get("openbank.anacredit.return.records").summary().totalAmount()).isEqualTo(1.0)
        assertThat(registry.get("openbank.anacredit.return.exclusions").summary().totalAmount()).isEqualTo(1.0)
    }

    @Test
    fun `an empty book still publishes a return with zero rows rather than no sample at all`(): Unit = runBlocking {
        // The under-reporting signal only works if a zero-row return is *sampled*: a missing series
        // is indistinguishable from a healthy idle service on a Prometheus dashboard.
        coEvery { exposures.listAll() } returns emptyList<CreditExposure>()

        service.build(LocalDate.parse("2026-06-30"))

        val records = registry.get("openbank.anacredit.return.records").summary()
        assertThat(records.count()).isEqualTo(1L)
        assertThat(records.totalAmount()).isEqualTo(0.0)
    }

    private fun command(instrumentType: InstrumentType, currency: String) = RegisterExposureCommand(
        instrumentId = "OD-9001",
        debtorId = "LE-ACME",
        debtorType = CounterpartyType.LEGAL_ENTITY,
        instrumentType = instrumentType,
        currency = currency,
        committedAmount = BigDecimal("40000.00"),
        drawnAmount = BigDecimal("12000.00"),
        committedAmountEur = BigDecimal("40000.00"),
        arrearsAmount = BigDecimal.ZERO,
        defaulted = false,
        originationDate = LocalDate.parse("2025-06-01"),
    )
}
