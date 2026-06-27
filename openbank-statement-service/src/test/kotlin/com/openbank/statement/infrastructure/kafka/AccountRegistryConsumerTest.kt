// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.statement.application.port.out.AccountRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Test
import java.util.UUID

class AccountRegistryConsumerTest {

    private val registry = mockk<AccountRegistry>()
    private val consumer = AccountRegistryConsumer(registry, ObjectMapper())

    @Test
    fun `AccountCreated is upserted idempotently into the registry`() {
        val accountId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        every { registry.upsertOpen(accountId, partyId, "CZK") } returns Uni.createFrom().voidItem()

        val payload = """
            {"eventType":"AccountCreated","aggregateId":"$accountId","partyId":"$partyId",
             "currency":"CZK","accountNumber":"CZ6508000000192000145399","version":1}
        """.trimIndent()

        consumer.consume(payload).await().indefinitely()

        verify(exactly = 1) { registry.upsertOpen(accountId, partyId, "CZK") }
    }

    @Test
    fun `unrelated event types are ignored`() {
        val payload = """{"eventType":"AccountStatusChanged","aggregateId":"${UUID.randomUUID()}"}"""

        consumer.consume(payload).await().indefinitely()

        verify(exactly = 0) { registry.upsertOpen(any(), any(), any()) }
    }

    @Test
    fun `a malformed AccountCreated is skipped, not nacked`() {
        // partyId + currency missing — must not throw and must not touch the registry.
        val payload = """{"eventType":"AccountCreated","aggregateId":"${UUID.randomUUID()}"}"""

        consumer.consume(payload).await().indefinitely()

        verify(exactly = 0) { registry.upsertOpen(any(), any(), any()) }
    }

    @Test
    fun `an unparseable payload is skipped`() {
        consumer.consume("this is not json").await().indefinitely()

        verify(exactly = 0) { registry.upsertOpen(any(), any(), any()) }
    }
}
