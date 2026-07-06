// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CnbRateProviderAdapterTest {

    private val client = mockk<CnbFeedClient>()
    private val adapter = CnbRateProviderAdapter(client).also { it.self = it }

    @Test
    fun `fetchFixing with no date requests the latest feed`() {
        every { client.daily(null) } returns Uni.createFrom().item("30.05.2026 #104\nEMU|euro|1|EUR|25,145")

        val result = runBlocking { adapter.fetchFixing(null) }

        assertThat(result).contains("EUR|25,145")
        verify(exactly = 1) { client.daily(null) }
    }

    @Test
    fun `fetchFixing with a date formats it as DD MM YYYY for the feed`() {
        every { client.daily("28.05.2026") } returns Uni.createFrom().item("28.05.2026 #103\nEMU|euro|1|EUR|25,100")

        val result = runBlocking { adapter.fetchFixing(LocalDate.of(2026, 5, 28)) }

        assertThat(result).contains("28.05.2026")
        verify(exactly = 1) { client.daily("28.05.2026") }
    }

    @Test
    fun `a persistent feed failure propagates after retries are exhausted`() {
        every { client.daily(any()) } returns Uni.createFrom().failure(RuntimeException("feed unreachable"))

        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { adapter.fetchFixing(null) }
        }.isInstanceOf(RuntimeException::class.java)
    }
}
