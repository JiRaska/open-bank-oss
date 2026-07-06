// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.client.BalanceResponse
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.MoneyMovementRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Unit coverage for the balance-api credit adapter, with a mocked REST client (Uni-wrapped). */
class BalanceCreditAdapterTest {

    private val settlement = Settlement(
        id = UUID.randomUUID(),
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("42.00"),
        currency = "EUR",
        status = SettlementStatus.DEBITED,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `credit posts the payee credit with a settlement-scoped reference`(): Unit = runBlocking {
        val balanceClient = mockk<BalanceRestClient>()
        val repo = mockk<SettlementRepository>()
        coEvery { repo.findById(settlement.id) } returns settlement
        val request = slot<MoneyMovementRequest>()
        every { balanceClient.credit(settlement.payeeAccountId, capture(request)) } returns
            Uni.createFrom().item(
                BalanceResponse(settlement.payeeAccountId, "EUR", BigDecimal("42"), BigDecimal("42")),
            )

        BalanceCreditAdapter(balanceClient, repo).credit(settlement.id)

        assertThat(request.captured.amount).isEqualByComparingTo("42.00")
        assertThat(request.captured.currency).isEqualTo("EUR")
        assertThat(request.captured.referenceId).isEqualTo("settlement-credit-${settlement.id}")
        verify { balanceClient.credit(settlement.payeeAccountId, any()) }
    }

    @Test
    fun `credit throws when the settlement is not found`(): Unit = runBlocking {
        val balanceClient = mockk<BalanceRestClient>()
        val repo = mockk<SettlementRepository>()
        val missingId = UUID.randomUUID()
        coEvery { repo.findById(missingId) } returns null

        assertThatThrownBy {
            runBlocking { BalanceCreditAdapter(balanceClient, repo).credit(missingId) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(missingId.toString())
    }

    @Test
    fun `credit propagates a failure from the balance client`(): Unit = runBlocking {
        val balanceClient = mockk<BalanceRestClient>()
        val repo = mockk<SettlementRepository>()
        coEvery { repo.findById(settlement.id) } returns settlement
        every { balanceClient.credit(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("balance-service down"))

        assertThatThrownBy {
            runBlocking { BalanceCreditAdapter(balanceClient, repo).credit(settlement.id) }
        }.isInstanceOf(RuntimeException::class.java).hasMessageContaining("balance-service down")
    }
}
