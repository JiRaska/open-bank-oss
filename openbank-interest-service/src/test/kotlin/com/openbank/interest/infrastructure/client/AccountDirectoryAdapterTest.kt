// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class AccountDirectoryAdapterTest {

    private val client = mockk<AccountServiceClient>()
    private val adapter = AccountDirectoryAdapter().also { it.client = client }

    @Test
    fun `a failed listing is propagated, not turned into an empty page`() {
        every { client.listActive(any(), any()) } returns Uni.createFrom().failure(IllegalStateException("503"))

        assertThatThrownBy { adapter.listActiveAccounts("cursor-2", 100).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("503")
    }

    @Test
    fun `a successful empty listing is an empty page`() {
        every { client.listActive(any(), any()) } returns Uni.createFrom().item(ActiveAccountsClientResponse())

        val page = adapter.listActiveAccounts(null, 100).await().indefinitely()

        assertThat(page.items).isEmpty()
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `a balance failure still degrades to null - it is counted per account by the run`() {
        every { client.getBalance(any()) } returns Uni.createFrom().failure(IllegalStateException("503"))

        assertThat(adapter.bookedBalance(UUID.randomUUID()).await().indefinitely()).isNull()
    }
}
