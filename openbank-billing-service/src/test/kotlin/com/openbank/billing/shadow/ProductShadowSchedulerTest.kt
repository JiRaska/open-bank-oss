// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.shadow

import com.openbank.billing.application.usecase.ProductShadowService
import com.openbank.billing.infrastructure.scheduler.ProductShadowScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProductShadowSchedulerTest {

    private fun scheduler(service: ProductShadowService, enabled: Boolean = true) =
        ProductShadowScheduler(service, mockk(relaxed = true), enabled, batchSize = 2, maxBatches = 3)

    @Test
    fun `drains until a short batch, never more than the per-run cap`(): Unit = runBlocking {
        val service = mockk<ProductShadowService>()
        coEvery { service.runOnce(2) } returnsMany listOf(2, 2, 1)
        assertThat(scheduler(service).runOnce()).isEqualTo(5)
        val flooded = mockk<ProductShadowService>()
        coEvery { flooded.runOnce(2) } returns 2
        assertThat(scheduler(flooded).runOnce()).isEqualTo(6)
        coVerify(exactly = 3) { flooded.runOnce(2) }
    }

    @Test
    fun `disabled does nothing`(): Unit = runBlocking {
        val service = mockk<ProductShadowService>()
        assertThat(scheduler(service, enabled = false).runOnce()).isEqualTo(0)
        coVerify(exactly = 0) { service.runOnce(any()) }
    }
}
