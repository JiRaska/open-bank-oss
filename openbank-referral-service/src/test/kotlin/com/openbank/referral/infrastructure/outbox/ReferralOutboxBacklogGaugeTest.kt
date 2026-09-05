// SPDX-License-Identifier: Apache-2.0
package com.openbank.referral.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReferralOutboxBacklogGaugeTest {
    @Test
    fun `registers referral backlog supplier whose value tracks processable rows`(): Unit = runBlocking {
        val repository = mockk<ReferralOutboxRepository>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("referral"), capture(supplier)) } returns Unit

        val gauge = ReferralOutboxBacklogGauge(repository, metrics)
        gauge.register()
        assertThat(supplier.captured().toLong()).isZero()

        coEvery { repository.countProcessable() } returns 2L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(2L)

        coEvery { repository.countProcessable() } returns 0L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isZero()
    }
}
