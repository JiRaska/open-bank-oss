// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SctInstOutboxDeadLetterGaugeTest {
    @Test
    fun `dead count stays visible when processable backlog is zero`(): Unit = runBlocking {
        val repository = mockk<SctInstOutboxRepository>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered(eq("sepa-instant"), capture(supplier)) } returns Unit
        coEvery { repository.countDead() } returns 1L
        coEvery { repository.countProcessable() } returns 0L

        val gauge = SctInstOutboxDeadLetterGauge(repository, metrics)
        gauge.register()
        gauge.refresh()

        assertThat(supplier.captured().toLong()).isEqualTo(1L)
    }
}
