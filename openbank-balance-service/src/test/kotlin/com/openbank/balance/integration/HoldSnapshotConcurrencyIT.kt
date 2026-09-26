// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.balance.integration

import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.LedgerProjectionUseCase
import com.openbank.balance.application.port.out.BalanceRepository
import com.openbank.balance.application.port.out.HoldRepository
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.domain.model.BalanceEventType
import com.openbank.balance.domain.model.BalanceHold
import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@QuarkusTestResource(BalanceOutboxWriteIT.DispatcherOffResource::class)
@TestSecurity(user = "snapshot-probe", roles = ["ROLE_API"])
class HoldSnapshotConcurrencyIT {
    @Inject lateinit var balances: BalanceRepository

    @Inject lateinit var holds: HoldRepository

    @Inject lateinit var projection: LedgerProjectionUseCase

    @Test
    fun `hold prepared before a projection cannot overwrite the newly booked funds`() {
        val account = UUID.randomUUID()
        given().contentType("application/json").body(mapOf("currency" to "CZK", "initialAmount" to "1000"))
            .post("/api/v1/balances/$account/initialize").then().statusCode(201)
        val original = onContext { requireNotNull(balances.findByAccountIdAndCurrency(account, "CZK")) }
        val reserved = original.withReservation(BigDecimal("100"))
        val hold = BalanceHold(
            UUID.randomUUID(), account, BigDecimal("100"), "CZK", "test reservation", UUID.randomUUID().toString(),
            null, OffsetDateTime.parse("2026-01-01T00:00:00Z"), null,
        )
        val event = BalanceEvent(
            UUID.randomUUID(), BalanceEventType.HOLD_PLACED, account, "CZK", hold.amount,
            reserved.bookedAmount, reserved.availableAmount, reserved.reservedAmount, hold.createdAt,
            "system:test", "SYSTEM", "balance-service",
        )
        onContext {
            projection.apply(
                AccountBookedChange(
                    account,
                    "CZK",
                    BigDecimal("50"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LocalDate.of(2026, 1, 1),
                    1,
                ),
            )
        }
        assertThatThrownBy { onContext { holds.saveWithEvent(hold, reserved, event) } }
            .isInstanceOf(jakarta.persistence.OptimisticLockException::class.java)
        val current = onContext { requireNotNull(balances.findByAccountIdAndCurrency(account, "CZK")) }
        assertThat(current.bookedAmount).isEqualByComparingTo("1050")
        assertThat(current.availableAmount).isEqualByComparingTo("1050")
        assertThat(current.reservedAmount).isEqualByComparingTo("0")
        assertThat(onContext { holds.findById(hold.id) }).isNull()
    }

    @Test
    fun `overdraft prepared before projection cannot overwrite the newly booked funds`() {
        val account = UUID.randomUUID()
        given().contentType("application/json").body(mapOf("currency" to "CZK", "initialAmount" to "1000"))
            .post("/api/v1/balances/$account/initialize").then().statusCode(201)
        val original = onContext { requireNotNull(balances.findByAccountIdAndCurrency(account, "CZK")) }
        val updated = original.copy(arrangedOverdraftLimit = BigDecimal("100"), version = original.version + 1)
        onContext {
            projection.apply(
                AccountBookedChange(
                    account,
                    "CZK",
                    BigDecimal("50"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LocalDate.of(2026, 1, 1),
                    1,
                ),
            )
        }
        assertThatThrownBy { onContext { balances.update(updated) } }
            .isInstanceOf(jakarta.persistence.OptimisticLockException::class.java)
        val current = onContext { requireNotNull(balances.findByAccountIdAndCurrency(account, "CZK")) }
        assertThat(current.bookedAmount).isEqualByComparingTo("1050")
        assertThat(current.availableAmount).isEqualByComparingTo("1050")
        assertThat(current.arrangedOverdraftLimit).isEqualByComparingTo("0")
    }

    @Test
    fun `release prepared across projection cannot release another holds funds`() {
        val account = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        given().contentType("application/json").body(mapOf("currency" to "CZK", "initialAmount" to "1000"))
            .post("/api/v1/balances/$account/initialize").then().statusCode(201)
        fun reserve(reference: UUID): UUID {
            val id = given().contentType("application/json").body(
                mapOf(
                    "currency" to "CZK",
                    "amount" to "100",
                    "reason" to "test cover",
                    "referenceId" to reference.toString(),
                ),
            ).post("/api/v1/balances/$account/holds").then().statusCode(201).extract().path<String>("id")
            return UUID.fromString(id)
        }
        val id = reserve(transaction)
        reserve(UUID.randomUUID())
        val original = onContext { requireNotNull(holds.findById(id)) }
        onContext {
            projection.apply(
                AccountBookedChange(
                    account,
                    "CZK",
                    BigDecimal("-100"),
                    UUID.randomUUID(),
                    transaction,
                    LocalDate.of(2026, 1, 1),
                    1,
                ),
            )
        }
        val current = onContext { requireNotNull(balances.findByAccountIdAndCurrency(account, "CZK")) }
        val attempted = current.releaseReservation(original.amount)
        val release = original.copy(releasedAt = OffsetDateTime.now())
        val event = BalanceEvent(
            UUID.randomUUID(), BalanceEventType.HOLD_RELEASED, account, "CZK", original.amount,
            attempted.bookedAmount, attempted.availableAmount, attempted.reservedAmount,
            requireNotNull(
                release.releasedAt,
            ),
            "system:test", "SYSTEM", "balance-service",
        )
        onContext { holds.releaseWithEvent(release, attempted, event) }
        val after = onContext { requireNotNull(balances.findByAccountIdAndCurrency(account, "CZK")) }
        assertThat(after.bookedAmount).isEqualByComparingTo("900")
        assertThat(after.availableAmount).isEqualByComparingTo("800")
        assertThat(after.reservedAmount).isEqualByComparingTo("100")
    }

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
