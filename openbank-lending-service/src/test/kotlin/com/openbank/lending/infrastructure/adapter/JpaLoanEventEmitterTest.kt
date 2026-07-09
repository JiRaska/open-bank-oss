// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.adapter

import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LendingOutboxRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A plain (non-Quarkus) unit test of the wiring — it cannot prove the write lands in a real
 * `lending_outbox` table (that needs a Panache-managed reactive session, i.e. a Quarkus boot; see
 * `com.openbank.lending.integration.LendingOutboxWriteIT`) but it does prove the adapter hands the
 * exact message to the repository and does not swallow a persistence failure.
 */
class JpaLoanEventEmitterTest {

    private val outbox = mockk<LendingOutboxRepository>()
    private val adapter = JpaLoanEventEmitter(outbox)

    private fun message() = LendingOutboxMessage(
        aggregateId = UUID.randomUUID(),
        eventType = "loan.disbursed",
        payload = """{"loanId":"x"}""",
    )

    @Test
    fun `emit persists the message via the outbox repository`() {
        val msg = message()
        every { outbox.persistInTransaction(msg) } returns Uni.createFrom().nullItem()

        val result = adapter.emit(msg).await().indefinitely()

        assertThat(result).isEqualTo(Unit)
        verify(exactly = 1) { outbox.persistInTransaction(msg) }
    }

    @Test
    fun `a persistence failure propagates instead of being swallowed`() {
        val msg = message()
        every { outbox.persistInTransaction(msg) } returns Uni.createFrom().failure(IllegalStateException("db down"))

        assertThatThrownBy { adapter.emit(msg).await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("db down")
    }
}
