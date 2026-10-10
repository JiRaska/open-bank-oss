// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.openbank.pension.application.port.out.Redemption
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The REST adapter's decisions against a fake pension-fund-service (ADR-0334 S8): the split, the
 * per-leg idempotency keys, and every refusal that must never become a guessed number.
 */
class PensionFundOrdersTest {

    private val equity = UUID.fromString("00000000-0000-4000-8000-0000000000e1")
    private val bonds = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val contract = UUID.randomUUID()

    private class FakeRegister(var holdings: List<HoldingDto>, val strategies: List<StrategyDto>) : FundRegister {
        val orders = linkedMapOf<String, OrderRequestDto>()

        override suspend fun holdings(contractId: UUID) = ContractValuationDto(contractId, holdings)

        override suspend fun placeOrder(
            contractId: UUID,
            idempotencyKey: String,
            order: OrderRequestDto,
        ): UnitOrderDto {
            orders.putIfAbsent(idempotencyKey, order) // the register deduplicates on the key
            return UnitOrderDto(UUID.nameUUIDFromBytes(idempotencyKey.toByteArray()), "PENDING")
        }

        override suspend fun strategies() = strategies

        var transactions: List<UnitTransactionDto> = emptyList()

        override suspend fun transactions(contractId: UUID) = transactions
    }

    private val balanced = StrategyDto(
        UUID.randomUUID(),
        "BALANCED",
        "ACTIVE",
        listOf(AllocationTargetDto(equity, BigDecimal("0.6")), AllocationTargetDto(bonds, BigDecimal("0.4"))),
    )
    private val clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC)

    private fun orders(register: FakeRegister, strategy: String? = "BALANCED") =
        PensionFundOrders(register, { strategy }, clock)

    @Test
    fun `a subscription is split by the strategy weights and the legs sum to the amount exactly`(): Unit = runBlocking {
        val register = FakeRegister(emptyList(), listOf(balanced))
        orders(register).subscribe(contract, BigDecimal("1000.01"), "CZK", "contribution:p-1")
        val legs = register.orders.values.associate { it.fundId to it.amount!! }
        assertThat(legs.getValue(equity)).isEqualByComparingTo("600.01")
        assertThat(legs.getValue(bonds)).isEqualByComparingTo("400.00")
        assertThat(legs.values.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1000.01")
        assertThat(register.orders.values.map { it.type }.toSet()).containsExactly("SUBSCRIBE")
    }

    @Test
    fun `a retried subscription re-sends the same per-fund keys, so the register buys once`(): Unit = runBlocking {
        val register = FakeRegister(emptyList(), listOf(balanced))
        orders(register).subscribe(contract, BigDecimal("100"), "CZK", "contribution:p-2")
        orders(register).subscribe(contract, BigDecimal("100"), "CZK", "contribution:p-2")
        assertThat(register.orders).hasSize(2)
        assertThat(register.orders.keys).allSatisfy { assertThat(it).hasSize(64) }
    }

    @Test
    fun `an unregistered strategy is a refusal, never a guess`() {
        val register = FakeRegister(emptyList(), listOf(balanced))
        assertThatThrownBy {
            runBlocking { orders(register, "DYNAMIC").subscribe(contract, BigDecimal.TEN, "CZK", "k") }
        }
            .isInstanceOf(FundAdministrationRefusedException::class.java)
        assertThat(register.orders).isEmpty()
    }

    @Test
    fun `valuation sums holdings at NAV and refuses a holding without a published NAV`(): Unit = runBlocking {
        val register = FakeRegister(
            listOf(
                HoldingDto(equity, BigDecimal("10"), BigDecimal("150"), BigDecimal("1500.00"), "CZK"),
                HoldingDto(bonds, BigDecimal("5"), BigDecimal("100"), BigDecimal("500.00"), "CZK"),
            ),
            listOf(balanced),
        )
        assertThat(orders(register).valuation(contract, "CZK").amount).isEqualByComparingTo("2000.00")

        register.holdings = register.holdings + HoldingDto(UUID.randomUUID(), BigDecimal("1"), null, null, "CZK")
        assertThatThrownBy { runBlocking { orders(register).valuation(contract, "CZK") } }
            .isInstanceOf(FundAdministrationRefusedException::class.java)
            .hasMessageContaining("no published NAV")
    }

    @Test
    fun `valuation refuses a holding in another currency`() {
        val register = FakeRegister(
            listOf(HoldingDto(equity, BigDecimal("1"), BigDecimal("1"), BigDecimal("1"), "EUR")),
            listOf(balanced),
        )
        assertThatThrownBy { runBlocking { orders(register).valuation(contract, "CZK") } }
            .isInstanceOf(FundAdministrationRefusedException::class.java)
    }

    @Test
    fun `a redemption sells pro rata, sells everything at the full value, and refuses more`(): Unit = runBlocking {
        val holdings = listOf(
            HoldingDto(equity, BigDecimal("10"), BigDecimal("150"), BigDecimal("1500.00"), "CZK"),
            HoldingDto(bonds, BigDecimal("5"), BigDecimal("100"), BigDecimal("500.00"), "CZK"),
        )
        val half = FakeRegister(holdings, listOf(balanced))
        val r = orders(half).redeem(contract, BigDecimal("1000"), "CZK", "term:1:redeem")
        assertThat(r).isEqualTo(Redemption("term:1:redeem", BigDecimal("1000.00")))
        val units = half.orders.values.associate { it.fundId to it.units!! }
        assertThat(units.getValue(equity)).isEqualByComparingTo("5")
        assertThat(units.getValue(bonds)).isEqualByComparingTo("2.5")

        val all = FakeRegister(holdings, listOf(balanced))
        orders(all).redeem(contract, BigDecimal("2000"), "CZK", "term:2:redeem")
        assertThat(all.orders.values.map { it.units }).containsExactly(BigDecimal("10"), BigDecimal("5"))

        assertThatThrownBy {
            runBlocking {
                orders(FakeRegister(holdings, listOf(balanced))).redeem(contract, BigDecimal("2000.01"), "CZK", "k")
            }
        }.isInstanceOf(FundAdministrationRefusedException::class.java)
    }

    @Test
    fun `a reversal re-buys the redeemed amount under its own key`(): Unit = runBlocking {
        val register = FakeRegister(emptyList(), listOf(balanced))
        orders(register).reverseRedemption(contract, Redemption("transfer-out:x", BigDecimal("50")), "CZK")
        assertThat(register.orders.values.fold(BigDecimal.ZERO) { a, o -> a + o.amount!! }).isEqualByComparingTo("50")
        assertThat(register.orders.keys).doesNotContain(PensionFundOrders.legKey("transfer-out:x", equity))
    }
}
