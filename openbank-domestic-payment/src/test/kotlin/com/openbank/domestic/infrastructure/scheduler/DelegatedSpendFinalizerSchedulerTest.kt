// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.scheduler

import com.openbank.domestic.application.port.`in`.FinalizeAbsentDelegatedSpendUseCase
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration

class DelegatedSpendFinalizerSchedulerTest {
    private val useCase = mockk<FinalizeAbsentDelegatedSpendUseCase>(relaxed = true)

    @Test
    fun `invalid money path config fails construction`() {
        assertThatThrownBy {
            scheduler(enabled = true, gracePeriod = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            scheduler(enabled = true, batchLimit = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `default off scheduler never finalizes a reservation`(): Unit = runBlocking {
        scheduler(enabled = false).finalizeAbsent()

        coVerify(exactly = 0) { useCase.finalizeBefore(any(), any()) }
    }

    private fun scheduler(
        enabled: Boolean,
        gracePeriod: Duration = Duration.ofMinutes(DEFAULT_GRACE_MINUTES),
        batchLimit: Int = DEFAULT_BATCH_LIMIT,
    ) = DelegatedSpendFinalizerScheduler(
        useCase = useCase,
        enabled = enabled,
        gracePeriod = gracePeriod,
        batchLimit = batchLimit,
        clock = Clock.systemUTC(),
    )

    private companion object {
        const val DEFAULT_GRACE_MINUTES = 10L
        const val DEFAULT_BATCH_LIMIT = 100
    }
}
