// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Property-based invariants for the balance/overdraft arithmetic (ADR-0011 L1). The example-based
 * [BalanceTest] pins specific cases; this suite asserts the money-conservation and overdraft-floor
 * invariants hold across hundreds of randomly generated balances and amounts — including overdrawn
 * starting states and amounts right at the floor. Domain-only, so no Testcontainers / Docker.
 */
class BalancePropertyTest {

    // Amounts in cents → BigDecimal scale 2. Signed range covers overdrawn (negative booked/available)
    // starting states; the floor is generated non-negative as the domain requires.
    private val signedArb = Arb.long(-10_000_000L, 10_000_000L).map { BigDecimal(it).movePointLeft(2) }
    private val nonNegArb = Arb.long(0L, 10_000_000L).map { BigDecimal(it).movePointLeft(2) }
    private val posArb = Arb.long(1L, 10_000_000L).map { BigDecimal(it).movePointLeft(2) }

    private val balanceArb: Arb<Balance> =
        Arb.bind(signedArb, signedArb, nonNegArb, nonNegArb) { booked, available, reserved, limit ->
            Balance(
                id = UUID.randomUUID(),
                accountId = UUID.randomUUID(),
                currency = "EUR",
                bookedAmount = booked,
                availableAmount = available,
                reservedAmount = reserved,
                pendingAmount = BigDecimal.ZERO,
                updatedAt = OffsetDateTime.now(),
                version = 7L,
                arrangedOverdraftLimit = limit,
            )
        }

    private fun Balance.floor(): BigDecimal = arrangedOverdraftLimit.negate()

    @Test
    fun `reservation conserves available plus reserved and respects the overdraft floor`(): Unit = runBlocking {
        checkAll(300, balanceArb, posArb) { bal, amount ->
            if (bal.availableAmount - amount >= bal.floor()) {
                val r = bal.withReservation(amount)
                assertThat(r.availableAmount + r.reservedAmount)
                    .isEqualByComparingTo(bal.availableAmount + bal.reservedAmount)
                assertThat(r.availableAmount).isEqualByComparingTo(bal.availableAmount - amount)
                assertThat(r.reservedAmount).isEqualByComparingTo(bal.reservedAmount + amount)
                assertThat(r.availableAmount).isGreaterThanOrEqualTo(bal.floor())
                assertThat(r.version).isEqualTo(bal.version + 1)
            } else {
                assertThatThrownBy { bal.withReservation(amount) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Insufficient funds")
            }
        }
    }

    @Test
    fun `reserve then release the same amount round-trips available and reserved`(): Unit = runBlocking {
        checkAll(300, balanceArb, posArb) { bal, amount ->
            if (bal.availableAmount - amount >= bal.floor()) {
                val roundTripped = bal.withReservation(amount).releaseReservation(amount)
                assertThat(roundTripped.availableAmount).isEqualByComparingTo(bal.availableAmount)
                assertThat(roundTripped.reservedAmount).isEqualByComparingTo(bal.reservedAmount)
            }
        }
    }

    @Test
    fun `release never drives reserved negative and conserves available plus reserved`(): Unit = runBlocking {
        checkAll(300, balanceArb, posArb) { bal, amount ->
            val r = bal.releaseReservation(amount)
            assertThat(r.reservedAmount).isGreaterThanOrEqualTo(BigDecimal.ZERO)
            assertThat(r.availableAmount + r.reservedAmount)
                .isEqualByComparingTo(bal.availableAmount + bal.reservedAmount)
            assertThat(r.reservedAmount).isEqualByComparingTo(bal.reservedAmount - amount.min(bal.reservedAmount))
        }
    }

    @Test
    fun `debit respects the overdraft floor and moves booked and available in lock-step`(): Unit = runBlocking {
        checkAll(300, balanceArb, posArb) { bal, amount ->
            if (bal.bookedAmount - amount >= bal.floor()) {
                val d = bal.applyDebit(amount)
                assertThat(d.bookedAmount).isEqualByComparingTo(bal.bookedAmount - amount)
                assertThat(d.availableAmount).isEqualByComparingTo(bal.availableAmount - amount)
                assertThat(d.bookedAmount).isGreaterThanOrEqualTo(bal.floor())
                assertThat(d.version).isEqualTo(bal.version + 1)
            } else {
                assertThatThrownBy { bal.applyDebit(amount) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Overdraft limit exceeded")
            }
        }
    }

    @Test
    fun `credit is unguarded and a credit then equal debit round-trips`(): Unit = runBlocking {
        checkAll(300, balanceArb, posArb) { bal, amount ->
            val c = bal.applyCredit(amount)
            assertThat(c.bookedAmount).isEqualByComparingTo(bal.bookedAmount + amount)
            assertThat(c.availableAmount).isEqualByComparingTo(bal.availableAmount + amount)

            // A debit of the same amount is allowed iff the original booked was within the floor;
            // then it undoes the credit exactly (c.booked - amount == bal.booked).
            if (bal.bookedAmount >= bal.floor()) {
                val back = c.applyDebit(amount)
                assertThat(back.bookedAmount).isEqualByComparingTo(bal.bookedAmount)
                assertThat(back.availableAmount).isEqualByComparingTo(bal.availableAmount)
            }
        }
    }

    @Test
    fun `booked-delta projection is unguarded and moves booked and available in lock-step`(): Unit = runBlocking {
        checkAll(300, balanceArb, signedArb) { bal, delta ->
            val r = bal.applyBookedDelta(delta)
            assertThat(r.bookedAmount).isEqualByComparingTo(bal.bookedAmount + delta)
            assertThat(r.availableAmount).isEqualByComparingTo(bal.availableAmount + delta)
        }
    }

    @Test
    fun `overdraft-used and is-overdrawn are consistent with booked sign`(): Unit = runBlocking {
        checkAll(300, balanceArb) { bal ->
            assertThat(bal.overdraftUsed()).isEqualByComparingTo(bal.bookedAmount.negate().max(BigDecimal.ZERO))
            assertThat(bal.isOverdrawn()).isEqualTo(bal.bookedAmount.signum() < 0)
        }
    }

    @Test
    fun `construction rejects a negative arranged overdraft limit`(): Unit = runBlocking {
        checkAll(300, posArb) { positiveLimit ->
            assertThatThrownBy {
                Balance(
                    id = UUID.randomUUID(),
                    accountId = UUID.randomUUID(),
                    currency = "EUR",
                    bookedAmount = BigDecimal.ZERO,
                    availableAmount = BigDecimal.ZERO,
                    reservedAmount = BigDecimal.ZERO,
                    pendingAmount = BigDecimal.ZERO,
                    updatedAt = OffsetDateTime.now(),
                    version = 0L,
                    arrangedOverdraftLimit = positiveLimit.negate(),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("arrangedOverdraftLimit must be non-negative")
        }
    }
}
