// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.domain.model.AccrualOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.function.Supplier

/**
 * The servicing posting loop must run the accrual pass for "today" as seen by the injected clock and
 * the configured batch size — and let a pass failure surface (the scheduler logs it; the next tick
 * retries) rather than silently swallowing it.
 */
class InterestAccrualSchedulerTest {

    private val accrual = mockk<AccrueInterestUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
    private val scheduler =
        InterestAccrualScheduler(accrual, batchSize = 250, clock = clock, domainMetrics = mockk(relaxed = true))

    @BeforeEach
    fun stubPanacheSession() {
        // The scheduler wraps the pass in Panache.withSession; no reactive session exists in a plain
        // unit test, so run the supplied work directly.
        mockkStatic(Panache::class)
        every { Panache.withSession(any<Supplier<Uni<Void>>>()) } answers {
            firstArg<Supplier<Uni<Void>>>().get()
        }
    }

    @AfterEach
    fun restorePanache() {
        unmockkStatic(Panache::class)
    }

    @Test
    fun `runs the pass for the clock's today with the configured batch size`() {
        val today = LocalDate.parse("2026-07-01")
        every { accrual.accrueDueInterest(today, 250) } returns
            Uni.createFrom().item(AccrualOutcome(asOf = today, installmentsAccrued = 3))

        val result = scheduler.runAccrualPass().await().indefinitely()

        assertThat(result).isNull()
        verify(exactly = 1) { accrual.accrueDueInterest(today, 250) }
    }

    @Test
    fun `completes quietly when nothing is due`() {
        val today = LocalDate.parse("2026-07-01")
        every { accrual.accrueDueInterest(today, 250) } returns
            Uni.createFrom().item(AccrualOutcome(asOf = today, installmentsAccrued = 0))

        scheduler.runAccrualPass().await().indefinitely()

        verify(exactly = 1) { accrual.accrueDueInterest(today, 250) }
    }

    @Test
    fun `a failing pass propagates so the scheduler tick is marked failed`() {
        every { accrual.accrueDueInterest(any(), any()) } returns
            Uni.createFrom().failure(IllegalStateException("ledger down"))

        assertThatThrownBy { scheduler.runAccrualPass().await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger down")
    }
}
