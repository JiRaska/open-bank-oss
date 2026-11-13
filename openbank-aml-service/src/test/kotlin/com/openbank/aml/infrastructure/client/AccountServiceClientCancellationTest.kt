// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The sweep's catch-all turns every lookup failure into `null`; it must NOT do that to a coroutine
 * cancellation, or a cancelled sweep keeps running as if the account merely had no owner.
 */
class AccountServiceClientCancellationTest {

    private fun clientFailingWith(failure: Throwable): AccountServiceClient {
        val rest = mockk<AccountServiceRestClient>()
        every { rest.getAccount(any()) } returns Uni.createFrom().failure<Response>(failure)
        return AccountServiceClient(rest)
    }

    @Test
    fun `a cancellation is rethrown, not swallowed into null`() {
        val client = clientFailingWith(CancellationException("sweep cancelled"))

        assertThatThrownBy { runBlocking { client.findPartyByAccountId(UUID.randomUUID()) } }
            .isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `an ordinary failure still resolves to null`() {
        val client = clientFailingWith(IllegalStateException("connection reset"))

        val owner = runBlocking { client.findPartyByAccountId(UUID.randomUUID()) }

        assertThat(owner).isNull()
    }
}
