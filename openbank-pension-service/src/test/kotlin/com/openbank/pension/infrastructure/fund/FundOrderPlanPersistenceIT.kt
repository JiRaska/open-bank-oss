// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.vertx.mutiny.sqlclient.Pool
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FundOrderPlanPersistenceIT {
    @Inject
    lateinit var pool: Pool

    @Inject
    lateinit var mapper: ObjectMapper

    @Test
    fun `new adapter replays persisted instructions after accepted redemption settles`(): Unit = runBlocking {
        val contract = UUID.randomUUID()
        val fund = UUID.randomUUID()
        val accepted = mutableListOf<OrderRequestDto>()
        var settled = false
        var loseResponse = true
        val register = object : FundRegister {
            override suspend fun holdings(contractId: UUID): ContractValuationDto {
                check(!settled) { "retry must not replan from settled holdings" }
                return ContractValuationDto(
                    contractId,
                    listOf(HoldingDto(fund, BigDecimal("10"), BigDecimal("100"), BigDecimal("1000"), "CZK")),
                )
            }
            override suspend fun placeOrder(
                contractId: UUID,
                idempotencyKey: String,
                order: OrderRequestDto,
            ): UnitOrderDto {
                accepted += order
                settled = true
                if (loseResponse) {
                    loseResponse = false
                    error("accepted response lost")
                }
                return UnitOrderDto(fund, "SETTLED")
            }
            override suspend fun strategies(): List<StrategyDto> = error("redemption needs no strategy")
            override suspend fun transactions(contractId: UUID): List<UnitTransactionDto> = emptyList()
        }
        val key = "persisted-redeem:$contract"
        val first = PensionFundOrders(register, { null }, Clock.systemUTC(), PgFundOrderPlans(pool, mapper))
        assertThatThrownBy {
            runBlocking { first.redeem(contract, BigDecimal("1000"), "CZK", key) }
        }.hasMessage("accepted response lost")
        val recreated = PensionFundOrders(register, { null }, Clock.systemUTC(), PgFundOrderPlans(pool, mapper))
        assertThat(recreated.redeem(contract, BigDecimal("1000"), "CZK", key).reference).isEqualTo(key)
        assertThat(accepted).hasSize(2)
        assertThat(accepted[1]).isEqualTo(accepted[0])
    }

    @Test
    fun `concurrent writers and a recreated repository see one immutable plan`(): Unit = runBlocking {
        val key = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val contract = UUID.randomUUID()
        val first = FundOrderPlan(
            key,
            contract,
            "SUBSCRIBE",
            BigDecimal("100"),
            "CZK",
            listOf(OrderRequestDto(UUID.randomUUID(), "SUBSCRIBE", amount = BigDecimal("100"))),
        )
        val second = first.copy(
            legs = listOf(OrderRequestDto(UUID.randomUUID(), "SUBSCRIBE", amount = BigDecimal("100"))),
        )
        val results = listOf(first, second).map { plan ->
            async { PgFundOrderPlans(pool, mapper).saveIfAbsent(plan) }
        }.awaitAll()
        assertThat(results[0]).isEqualTo(results[1])
        val stored = PgFundOrderPlans(pool, mapper).find(key)
        assertThat(stored).isEqualTo(results[0])
        assertThatThrownBy {
            requireNotNull(stored).requireRequest(contract, "SUBSCRIBE", BigDecimal("200"), "CZK")
        }.hasMessage("fund operation key reused for a different request")
    }
}
