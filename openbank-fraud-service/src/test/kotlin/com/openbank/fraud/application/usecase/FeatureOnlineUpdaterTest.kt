// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.libs.domain.feature.OnlineFeatureStore
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H1
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H24
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

class FeatureOnlineUpdaterTest {

    @Test
    fun `records one windowed increment per phase-1 feature with the event-time bucket and surrogate offset`() {
        val store = mockk<OnlineFeatureStore>()
        coEvery { store.incrementWindowed(any(), any(), any(), any(), any()) } just Runs
        val updater = FeatureOnlineUpdater(store)

        val entity = "acc-1"
        val occurredAt = Instant.parse("2026-06-29T10:30:00Z")

        runBlocking { updater.onTransactionInitiated(entity, occurredAt) }

        coVerify(exactly = 1) {
            store.incrementWindowed(
                VELOCITY_TXN_COUNT_H1,
                entity,
                occurredAt,
                Instant.parse("2026-06-29T10:00:00Z"),
                occurredAt.toEpochMilli(),
            )
        }
        coVerify(exactly = 1) {
            store.incrementWindowed(
                VELOCITY_TXN_COUNT_H24,
                entity,
                occurredAt,
                Instant.parse("2026-06-29T00:00:00Z"),
                occurredAt.toEpochMilli(),
            )
        }
    }
}
