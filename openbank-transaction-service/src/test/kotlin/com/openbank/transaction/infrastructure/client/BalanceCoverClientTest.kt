// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Unit coverage for [BalanceCoverClient] — delegation to [BalanceCoverRestClient]. */
class BalanceCoverClientTest {

    private val restClient: BalanceCoverRestClient = mockk()
    private val client = BalanceCoverClient(restClient)

    @Test
    fun `placeHold builds the request body and returns the hold id`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val holdId = UUID.randomUUID()
        every {
            restClient.placeHold(
                accountId,
                PlaceHoldBody(BigDecimal("50.00"), "CZK", "sca-pending", "ref-1", 300L),
            )
        } returns Uni.createFrom().item(HoldResponse(holdId))

        val result = client.placeHold(accountId, BigDecimal("50.00"), "CZK", "sca-pending", "ref-1", 300L)

        assertThat(result).isEqualTo(holdId)
    }

    @Test
    fun `releaseHold delegates to the rest client`(): Unit = runBlocking {
        val holdId = UUID.randomUUID()
        every { restClient.releaseHold(holdId) } returns Uni.createFrom().item(HoldResponse(holdId))

        client.releaseHold(holdId)

        verify(exactly = 1) { restClient.releaseHold(holdId) }
    }
}
