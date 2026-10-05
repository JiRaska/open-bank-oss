// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.domain.model.LedgerNostroLine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FundingBalanceSchedulerTest {
    @Test
    fun `yesterday in Prague is read and only a negative balance raises overdraft`(): Unit = runBlocking {
        val yesterday = LocalDate.parse("2026-07-15")
        val calls = mutableListOf<Triple<String, String, LocalDate>>()
        val ledger = object : LedgerReadPort {
            override suspend fun nostroLines(glCode: String, from: LocalDate, to: LocalDate): List<LedgerNostroLine> =
                emptyList()

            override suspend fun accountBalance(glCode: String, currency: String, asOf: LocalDate): BigDecimal? {
                calls += Triple(glCode, currency, asOf)
                return when (glCode) {
                    "1001" -> BigDecimal("-0.01")
                    "1002" -> BigDecimal.ZERO
                    else -> null // 1010 has not been seeded yet.
                }
            }
        }
        val registry = SimpleMeterRegistry()
        val registryInstance = mockk<Instance<MeterRegistry>>()
        every { registryInstance.isResolvable } returns true
        every { registryInstance.get() } returns registry
        val metrics = DomainMetrics().apply { this.registryInstance = registryInstance }
        val clock = Clock.fixed(Instant.parse("2026-07-15T22:30:00Z"), ZoneOffset.UTC)
        val scheduler = FundingBalanceScheduler(ledger, metrics, registry, clock)
        scheduler.register(StartupEvent())
        assertThat(registry.find("openbank.treasury.funding.overdraft").tag("account", "1001").gauge()!!.value())
            .isNaN()

        scheduler.run()
        assertThat(calls).containsExactly(
            Triple("1001", "CZK", yesterday),
            Triple("1002", "EUR", yesterday),
            Triple("1010", "CZK", yesterday),
        )
        assertThat(registry.find("openbank.treasury.funding.overdraft").tag("account", "1001").gauge()!!.value())
            .isEqualTo(0.01)
        assertThat(registry.find("openbank.treasury.funding.overdraft").tag("account", "1002").gauge()!!.value())
            .isZero()
        assertThat(registry.find("openbank.treasury.funding.overdraft").tag("account", "1010").gauge()!!.value())
            .isNaN()
    }
}
