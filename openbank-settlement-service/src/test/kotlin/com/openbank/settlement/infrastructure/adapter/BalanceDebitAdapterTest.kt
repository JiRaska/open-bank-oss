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

/** Unit coverage for the balance-api debit adapter, with a mocked REST client (Uni-wrapped). */
class BalanceDebitAdapterTest {

    private val settlement = Settlement(
        id = UUID.randomUUID(),
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("125.50"),
        currency = "CZK",
        status = SettlementStatus.PENDING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `debit posts the payer debit with a settlement-scoped reference`(): Unit = runBlocking {
        val balanceClient = mockk<BalanceRestClient>()
        val repo = mockk<SettlementRepository>()
        coEvery { repo.findById(settlement.id) } returns settlement
        val request = slot<MoneyMovementRequest>()
        every { balanceClient.debit(settlement.payerAccountId, capture(request)) } returns
            Uni.createFrom().item(
                BalanceResponse(settlement.payerAccountId, "CZK", BigDecimal("100"), BigDecimal("100")),
            )

        BalanceDebitAdapter(balanceClient, repo).debit(settlement.id)

        assertThat(request.captured.amount).isEqualByComparingTo("125.50")
        assertThat(request.captured.currency).isEqualTo("CZK")
        assertThat(request.captured.referenceId).isEqualTo("settlement-debit-${settlement.id}")
        verify { balanceClient.debit(settlement.payerAccountId, any()) }
    }

    @Test
    fun `debit throws when the settlement is not found`(): Unit = runBlocking {
        val balanceClient = mockk<BalanceRestClient>()
        val repo = mockk<SettlementRepository>()
        val missingId = UUID.randomUUID()
        coEvery { repo.findById(missingId) } returns null

        assertThatThrownBy {
            runBlocking { BalanceDebitAdapter(balanceClient, repo).debit(missingId) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(missingId.toString())
    }

    @Test
    fun `debit propagates a failure from the balance client`(): Unit = runBlocking {
        val balanceClient = mockk<BalanceRestClient>()
        val repo = mockk<SettlementRepository>()
        coEvery { repo.findById(settlement.id) } returns settlement
        every { balanceClient.debit(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("balance-service down"))

        assertThatThrownBy {
            runBlocking { BalanceDebitAdapter(balanceClient, repo).debit(settlement.id) }
        }.isInstanceOf(RuntimeException::class.java).hasMessageContaining("balance-service down")
    }
}
