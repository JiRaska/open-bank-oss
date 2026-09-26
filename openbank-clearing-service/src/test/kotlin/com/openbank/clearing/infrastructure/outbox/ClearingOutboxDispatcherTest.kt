// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.clearing.infrastructure.outbox

import com.openbank.clearing.application.port.out.ClearingOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ClearingOutboxDispatcherTest {

    @Test
    fun `configured batch size reaches the atomic repository claim`(): Unit = runBlocking {
        val repository = mockk<ClearingOutboxRepository>()
        coEvery { repository.claimProcessable(250, any()) } returns emptyList()

        ClearingOutboxDispatcher(
            repo = repository,
            publisher = mockk<OutboxEventPublisher>(),
            dispatchEnabled = true,
            dispatchBatchSize = 250,
            metrics = mockk(relaxed = true),
        ).dispatch()

        coVerify(exactly = 1) { repository.claimProcessable(250, any()) }
        coVerify(exactly = 0) { repository.claimProcessable(25, any()) }
    }

    @Test
    fun `disabled dispatcher does not claim a burst`(): Unit = runBlocking {
        val repository = mockk<ClearingOutboxRepository>()

        ClearingOutboxDispatcher(
            repo = repository,
            publisher = mockk<OutboxEventPublisher>(),
            dispatchEnabled = false,
            dispatchBatchSize = 250,
            metrics = mockk(relaxed = true),
        ).dispatch()

        coVerify(exactly = 0) { repository.claimProcessable(any(), any()) }
    }
}
