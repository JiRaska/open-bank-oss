// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Permutation-order-independence property (ADR-0011, issue #469 item 5). [BalancePropertyTest]'s
 * "booked-delta projection is unguarded and moves booked and available in lock-step" proves
 * order-independence for a SINGLE delta; this proves the stronger claim across a whole SEQUENCE:
 * folding a batch of ledger-projected booked deltas into a [Balance] is commutative — the final
 * projected balance does not depend on the order the events are replayed in.
 *
 * Scoped deliberately to [Balance.applyBookedDelta]: it is the one balance mutation with no
 * overdraft-floor guard (ADR-0039 Phase D — the read-model catching up to an already-posted ledger
 * fact, not a new spend decision), so it is unconditionally commutative/associative (plain signed
 * `BigDecimal` addition). [Balance.applyDebit]/[Balance.withReservation] carry a `require(...)`
 * overdraft-floor guard that can throw for one ordering of a permutation and not another even
 * though the final sum would match — reordering those is not a safe general property.
 */
class BalanceProjectionOrderPropertyTest {

    private val signedArb = Arb.long(-10_000_000L, 10_000_000L).map { BigDecimal(it).movePointLeft(2) }
    private val nonNegArb = Arb.long(0L, 10_000_000L).map { BigDecimal(it).movePointLeft(2) }

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

    private fun fold(bal: Balance, deltas: List<BigDecimal>): Balance =
        deltas.fold(bal) { b, d -> b.applyBookedDelta(d) }

    @Test
    fun `folding a sequence of booked deltas is independent of application order`(): Unit = runBlocking {
        checkAll(200, balanceArb, Arb.list(signedArb, 1..8)) { bal, deltas ->
            val inOrder = fold(bal, deltas)

            repeat(3) {
                val reordered = fold(bal, deltas.shuffled())
                assertThat(reordered.bookedAmount).isEqualByComparingTo(inOrder.bookedAmount)
                assertThat(reordered.availableAmount).isEqualByComparingTo(inOrder.availableAmount)
                assertThat(reordered.version).isEqualTo(inOrder.version)
            }
        }
    }

    @Test
    fun `folding a sequence of booked deltas equals applying their sum in one step`(): Unit = runBlocking {
        checkAll(200, balanceArb, Arb.list(signedArb, 0..8)) { bal, deltas ->
            val folded = fold(bal, deltas)
            val sum = deltas.fold(BigDecimal.ZERO) { acc, d -> acc + d }
            val oneStep = bal.applyBookedDelta(sum)

            assertThat(folded.bookedAmount).isEqualByComparingTo(oneStep.bookedAmount)
            assertThat(folded.availableAmount).isEqualByComparingTo(oneStep.availableAmount)
        }
    }
}
