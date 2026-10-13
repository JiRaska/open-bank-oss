// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.fund

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.application.port.out.ContributionOrigin
import com.openbank.pension.domain.contribution.ContributionSource
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.vertx.mutiny.sqlclient.Pool
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SubscriptionPlansPostgresIT {
    @Inject lateinit var pool: Pool

    @Inject lateinit var mapper: ObjectMapper

    @Test
    fun `plan survives repository recreation and refuses a changed instruction`(): Unit = runBlocking {
        val contract = seededContract()
        val key = "contribution:${UUID.randomUUID()}"
        val original = plan(contract, key, UUID.randomUUID(), "60.00")
        val store = PgSubscriptionPlans(pool, mapper)

        val saved = store.insertIfAbsent(original)
        assertThat(saved.legs).isEqualTo(original.legs)
        assertThat(saved.amount).isEqualByComparingTo(original.amount)
        assertThat(PgSubscriptionPlans(pool, mapper).find(contract, key)).isEqualTo(saved)
        assertThat(store.insertIfAbsent(plan(contract, key, UUID.randomUUID(), "100.00"))).isEqualTo(saved)
        assertThatThrownBy { original.requireSameInstruction(BigDecimal("101"), "CZK") }
            .hasMessageContaining("Idempotency-Key reused")
    }

    @Test
    fun `long contribution key and full-size compensation amount persist without truncation`(): Unit = runBlocking {
        val contract = seededContract()
        val longKey = "contribution:" + "x".repeat(128)
        val amount = BigDecimal("1000000000")
        val full = SubscriptionPlan(
            contract,
            longKey,
            amount,
            "CZK",
            listOf(SubscriptionLeg(UUID.randomUUID(), amount)),
        )
        val store = PgSubscriptionPlans(pool, mapper)
        val saved = store.insertIfAbsent(full)
        assertThat(saved.amount).isEqualByComparingTo(amount)
        assertThat(saved.idempotencyKey).isEqualTo(longKey)
        assertThat(PgSubscriptionPlans(pool, mapper).find(contract, longKey)).isEqualTo(saved)
    }

    @Test
    fun `batch placement mode survives repository recreation`(): Unit = runBlocking {
        val contract = seededContract()
        val key = "contribution:${UUID.randomUUID()}"
        val original = SubscriptionPlan(
            contract,
            key,
            BigDecimal("100.00"),
            "CZK",
            listOf(SubscriptionLeg(UUID.randomUUID(), BigDecimal("100.00"))),
            SubscriptionPlacementMode.BATCH,
        )
        val saved = PgSubscriptionPlans(pool, mapper).insertIfAbsent(original)
        assertThat(saved.placementMode).isEqualTo(SubscriptionPlacementMode.BATCH)
        assertThat(PgSubscriptionPlans(pool, mapper).find(contract, key)).isEqualTo(saved)
    }

    @Test
    fun `partial subscription retains original plan after restart`(): Unit = runBlocking {
        val contract = seededContract()
        val key = "contribution:${UUID.randomUUID()}"
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val newFund = UUID.randomUUID()
        val sent = linkedMapOf<String, OrderRequestDto>()
        var failSecondOnce = true
        val register = object : FundRegister {
            override suspend fun holdings(contractId: UUID) = ContractValuationDto(contractId)

            override suspend fun transactions(contractId: UUID): List<UnitTransactionDto> = emptyList()

            override suspend fun strategies() = listOf(
                StrategyDto(
                    UUID.randomUUID(),
                    "BALANCED",
                    "ACTIVE",
                    listOf(
                        AllocationTargetDto(first, BigDecimal("0.6")),
                        AllocationTargetDto(second, BigDecimal("0.4")),
                    ),
                    1,
                ),
                StrategyDto(
                    UUID.randomUUID(),
                    "DYNAMIC",
                    "ACTIVE",
                    listOf(AllocationTargetDto(newFund, BigDecimal.ONE)),
                    1,
                ),
            )

            override suspend fun placeOrder(
                contractId: UUID,
                idempotencyKey: String,
                order: OrderRequestDto,
            ): UnitOrderDto {
                if (order.fundId == second && failSecondOnce) {
                    failSecondOnce = false
                    error("transient second-leg failure")
                }
                sent.putIfAbsent(idempotencyKey, order)
                return UnitOrderDto(UUID.nameUUIDFromBytes(idempotencyKey.toByteArray()), "PENDING")
            }

            override suspend fun placeSubscriptionBatch(
                contractId: UUID,
                idempotencyKey: String,
                request: SubscriptionBatchRequestDto,
            ): List<SubscriptionBatchOrderDto> = error("legacy replay must use single-leg orders")
        }

        PgSubscriptionPlans(pool, mapper).insertIfAbsent(
            SubscriptionPlan(
                contract,
                key,
                BigDecimal("100.1234"),
                "CZK",
                listOf(SubscriptionLeg(first, BigDecimal("60.07")), SubscriptionLeg(second, BigDecimal("40.05"))),
            ),
        )

        val initial = PensionFundOrders(
            register,
            { _, date -> testElection("BALANCED", date) },
            Clock.systemUTC(),
            PgSubscriptionPlans(pool, mapper),
        )
        assertThatThrownBy { runBlocking { initial.subscribe(contract, BigDecimal("100.1234"), "CZK", key) } }
            .hasMessageContaining("transient second-leg failure")
        assertThat(sent.values.map { it.fundId }).containsExactly(first)

        val restarted = PensionFundOrders(
            register,
            { _, date -> testElection("DYNAMIC", date) },
            Clock.systemUTC(),
            PgSubscriptionPlans(pool, mapper),
        )
        restarted.subscribe(contract, BigDecimal("100.1234"), "CZK", key)
        assertThat(sent.values.map { it.fundId }).containsExactlyInAnyOrder(first, second)
        assertThat(sent.values.fold(BigDecimal.ZERO) { total, order -> total + order.amount!! })
            .isEqualByComparingTo("100.12")
    }

    @Test
    fun `lost batch response after remote acceptance replays one frozen plan after restart`(): Unit = runBlocking {
        val contract = seededContract()
        val key = "contribution:${UUID.randomUUID()}"
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val cash = UUID.randomUUID()
        val balancedId = UUID.randomUUID()
        val origin =
            ContributionOrigin(
                UUID.randomUUID(),
                key.removePrefix("contribution:"),
                ContributionSource.PARTICIPANT,
                LocalDate.parse("2026-10-07"),
            )
        val firstClock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC)
        val laterClock = Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC)
        val accepted = linkedMapOf<String, List<SubscriptionBatchOrderDto>>()
        val requests = mutableListOf<SubscriptionBatchRequestDto>()
        var loseFirstReply = true
        var batchCalls = 0
        val register = object : FundRegister {
            override suspend fun holdings(contractId: UUID) = ContractValuationDto(contractId)

            override suspend fun transactions(contractId: UUID): List<UnitTransactionDto> = emptyList()

            override suspend fun strategies() = listOf(
                StrategyDto(
                    balancedId,
                    "BALANCED",
                    "ACTIVE",
                    listOf(
                        AllocationTargetDto(first, BigDecimal("0.6")),
                        AllocationTargetDto(second, BigDecimal("0.4")),
                    ),
                    7,
                ),
                StrategyDto(
                    UUID.randomUUID(),
                    "DYNAMIC",
                    "ACTIVE",
                    listOf(AllocationTargetDto(cash, BigDecimal.ONE)),
                    1,
                ),
            )

            override suspend fun placeOrder(
                contractId: UUID,
                idempotencyKey: String,
                order: OrderRequestDto,
            ): UnitOrderDto = error("new plan must use atomic batch")

            override suspend fun placeSubscriptionBatch(
                contractId: UUID,
                idempotencyKey: String,
                request: SubscriptionBatchRequestDto,
            ): List<SubscriptionBatchOrderDto> {
                batchCalls++
                requests += request
                val receipt = accepted.getOrPut(idempotencyKey) {
                    request.legs.map { leg ->
                        SubscriptionBatchOrderDto(
                            UUID.nameUUIDFromBytes(leg.idempotencyKey.toByteArray()),
                            leg.fundId,
                            leg.amount,
                        )
                    }
                }
                if (loseFirstReply) {
                    loseFirstReply = false
                    error("response lost after remote acceptance")
                }
                return receipt
            }
        }

        val initial = PensionFundOrders(
            register,
            { _, date -> testElection("BALANCED", date) },
            firstClock,
            PgSubscriptionPlans(pool, mapper),
        )
        assertThatThrownBy { runBlocking { initial.subscribe(contract, BigDecimal("100.01"), "CZK", key, origin) } }
            .hasMessageContaining("response lost after remote acceptance")
        val frozen = checkNotNull(PgSubscriptionPlans(pool, mapper).find(contract, key))
        assertFrozenDecision(frozen, origin, balancedId)

        val restarted = PensionFundOrders(
            register,
            { _, date -> testElection("DYNAMIC", date) },
            laterClock,
            PgSubscriptionPlans(pool, mapper),
        )
        val orderRef = restarted.subscribe(contract, BigDecimal("100.01"), "CZK", key, origin)
        assertThat(orderRef).isNotBlank()
        assertThat(batchCalls).isEqualTo(2)
        assertThat(accepted).hasSize(1)
        assertThat(requests).hasSize(2)
        assertThat(requests[1]).isEqualTo(requests[0])
        assertThat(requests[1].legs.map { it.idempotencyKey }).allSatisfy { assertThat(it).hasSize(64) }
        assertThat(accepted.getValue(key).map { it.fundId }).containsExactly(first, second)
        assertThat(accepted.getValue(key).fold(BigDecimal.ZERO) { total, order -> total + order.amount })
            .isEqualByComparingTo("100.01")
        assertChangedInstructionRejected(restarted, contract, key, origin)
        assertThat(batchCalls).isEqualTo(2)
    }

    private fun assertChangedInstructionRejected(
        restarted: PensionFundOrders,
        contract: UUID,
        key: String,
        origin: ContributionOrigin,
    ) {
        assertThatThrownBy { runBlocking { restarted.subscribe(contract, BigDecimal("100.02"), "CZK", key, origin) } }
            .hasMessageContaining("Idempotency-Key reused")
        assertThatThrownBy {
            runBlocking {
                restarted.subscribe(
                    contract,
                    BigDecimal("100.01"),
                    "CZK",
                    key,
                    origin.copy(contributionId = UUID.randomUUID()),
                )
            }
        }.hasMessageContaining("different contribution")
    }

    private fun assertFrozenDecision(frozen: SubscriptionPlan, origin: ContributionOrigin, balancedId: UUID) {
        assertThat(frozen.placementMode).isEqualTo(SubscriptionPlacementMode.BATCH)
        assertThat(frozen.decision?.origin).isEqualTo(origin)
        assertThat(frozen.decision?.providerEntityId)
            .isEqualTo(testElection("BALANCED", LocalDate.parse("2026-10-09")).providerEntityId)
        assertThat(frozen.decision?.selectedOn).isEqualTo(LocalDate.parse("2026-10-09"))
        assertThat(frozen.decision?.strategyId).isEqualTo(balancedId)
        assertThat(frozen.decision?.strategyVersion).isEqualTo(7)
        assertThat(frozen.decision?.sourceCurrency).isEqualTo("CZK")
        assertThat(frozen.decision?.targetCurrency).isEqualTo("CZK")
        assertThat(frozen.decision?.roundingMode).isEqualTo("HALF_EVEN")
        assertThat(frozen.legs.map { it.orderKey }).allSatisfy { assertThat(it).hasSize(64) }
    }

    @Test
    fun `concurrent different first plans persist exactly one winner`() {
        val contract = seededContract()
        val key = "contribution:${UUID.randomUUID()}"
        val left = plan(contract, key, UUID.randomUUID(), "60.00")
        val right = plan(contract, key, UUID.randomUUID(), "100.00")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(left, right).map { candidate ->
                executor.submit<SubscriptionPlan> {
                    runBlocking { PgSubscriptionPlans(pool, mapper).insertIfAbsent(candidate) }
                }
            }
            val results = writes.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(results[0]).isEqualTo(results[1])
            assertThat(results[0].legs).isIn(left.legs, right.legs)
            assertThat(results[0].amount).isEqualByComparingTo("100")
            assertThat(runBlocking { PgSubscriptionPlans(pool, mapper).find(contract, key) }).isEqualTo(results[0])
        } finally {
            executor.shutdownNow()
        }
    }

    private fun plan(contract: UUID, key: String, fund: UUID, firstAmount: String) = SubscriptionPlan(
        contract,
        key,
        BigDecimal("100"),
        "CZK",
        listOf(SubscriptionLeg(fund, BigDecimal(firstAmount))) +
            (BigDecimal("100") - BigDecimal(firstAmount)).takeIf { it.signum() > 0 }?.let {
                listOf(SubscriptionLeg(UUID.randomUUID(), it))
            }.orEmpty(),
    )

    private fun seededContract(): UUID {
        val id = UUID.randomUUID()
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { db ->
            db.prepareStatement(
                """INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction,
                   pack_version, provider_entity_id, provider_type, participant_birth_date, status,
                   contribution_amount, contribution_currency, contribution_frequency, created_at, updated_at)
                   VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 2, ?,
                   'PENSION_COMPANY', DATE '1985-05-05', 'DRAFT', 100, 'CZK', 'MONTHLY', now(), now())""",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.executeUpdate()
            }
        }
        return id
    }
}
