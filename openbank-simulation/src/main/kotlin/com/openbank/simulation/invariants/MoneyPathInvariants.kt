// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.runner.World
import com.openbank.transaction.domain.saga.SagaState
import java.math.BigDecimal

/**
 * The ADR-0100 Layer-3 invariant set for the money path. Each is a pure function of the
 * [World] state and is checked after every step; the first to break aborts the run with a
 * reproducible seed.
 */
object MoneyPathInvariants {

    /** `Σ debit == Σ credit` across the whole ledger ⇒ net movement per currency is zero. */
    val conservationOfMoney = object : Invariant {
        override val name = "ledger-conservation-of-money"
        override fun check(world: World): Violation? {
            world.ledger.lineNetByCurrency().forEach { (currency, net) ->
                if (net.signum() != 0) {
                    return Violation(name, "currency $currency nets to $net, expected 0")
                }
            }
            return null
        }
    }

    /** No balance's available amount may fall below its overdraft floor. */
    val noNegativeBalance = object : Invariant {
        override val name = "no-negative-balance"
        override fun check(world: World): Violation? {
            world.balances.all().forEach { balance ->
                val floor = balance.arrangedOverdraftLimit.negate()
                if (balance.available() < floor) {
                    val key = AccountCurrency(balance.accountId, balance.currency)
                    return Violation(
                        name,
                        "account $key available=${balance.available()} < floor=$floor",
                    )
                }
            }
            return null
        }
    }

    /**
     * Every customer balance's projected booked movement equals the ledger's net credit-positive
     * delta for that account. This is the idempotency/projection invariant: duplicate or
     * re-delivered events must not move it (a correct, dedup-guarded consumer keeps it exact).
     */
    val projectionConsistency = object : Invariant {
        override val name = "ledger-balance-projection-consistency"
        override fun check(world: World): Violation? {
            val netDeltas = world.ledger.netDeltas()
            world.balances.all().forEach { balance ->
                val key = AccountCurrency(balance.accountId, balance.currency)
                val expected = world.openingBookedOf(key) + (netDeltas[key] ?: BigDecimal.ZERO)
                if (balance.bookedAmount.compareTo(expected) != 0) {
                    return Violation(
                        name,
                        "account $key booked=${balance.bookedAmount} but ledger projects $expected",
                    )
                }
            }
            return null
        }
    }

    /** No saga may be left in a non-terminal state once a step has fully settled. */
    val compensationCompleteness = object : Invariant {
        override val name = "compensation-completeness"
        override fun check(world: World): Violation? {
            world.sagas.forEach { saga ->
                if (!saga.isTerminal) {
                    return Violation(name, "saga ${saga.id} stuck in ${saga.state}")
                }
            }
            return null
        }
    }

    /** The audit chain verifies and carries at least one record per executed saga. */
    val auditCompleteness = object : Invariant {
        override val name = "audit-completeness"
        override fun check(world: World): Violation? {
            if (!world.audit.verifyChain()) {
                return Violation(name, "audit hash chain failed to verify")
            }
            if (world.audit.size() < world.sagas.size) {
                return Violation(
                    name,
                    "audit has ${world.audit.size()} records for ${world.sagas.size} sagas",
                )
            }
            return null
        }
    }

    /** All Layer-3 invariants, in check order. */
    val ALL: List<Invariant> = listOf(
        conservationOfMoney,
        noNegativeBalance,
        projectionConsistency,
        compensationCompleteness,
        auditCompleteness,
    )

    /** A terminal-state guard used by the saga orchestrator. */
    fun isTerminal(state: SagaState): Boolean =
        state == SagaState.COMPLETED || state == SagaState.COMPENSATED || state == SagaState.FAILED
}
