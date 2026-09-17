// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ScaChallengeClientBindingTest {
    @Test
    fun `provider's consumed statutory binding reaches the decision gate unchanged`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val operationId = UUID.randomUUID().toString()
        val hash = "b".repeat(64)
        val consumedAt = "2026-09-17T12:00:00Z"
        val provider = mockk<ScaServiceRestClient>()
        coEvery { provider.getChallenge(id) } returns ScaChallengeClientResponse(
            id = id,
            partyId = actor,
            purpose = "DELEGATION_STATUTORY_APPROVAL",
            status = "COMPLETED",
            consumedAt = consumedAt,
            operationId = operationId,
            operationHash = hash,
        )

        val actual = ResilientScaChallengeClient(provider).getChallenge(id)

        assertThat(actual.partyId).isEqualTo(actor)
        assertThat(actual.consumedAt).isEqualTo(consumedAt)
        assertThat(actual.operationId).isEqualTo(operationId)
        assertThat(actual.operationHash).isEqualTo(hash)
    }
}
