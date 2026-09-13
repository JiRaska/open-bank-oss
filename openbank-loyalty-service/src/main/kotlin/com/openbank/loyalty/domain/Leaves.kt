// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

/**
 * A quantity of Lístky (leaves) — ADR-0282 D1's closed-loop unit of bank obligation.
 *
 * Deliberately NOT a currency-typed amount, and carrying no arithmetic, comparison or conversion
 * toward any monetary type in this codebase (`Money`, `MinorUnits`, an `openbank-ledger-service`
 * amount, or their equivalents in `openbank-libs-domain`). This is the same boundary
 * `openbank-engagement-service`'s `Points` draws for its activity counter, and for the same
 * reason: giving this type a `toMoney()` or an `operator times(exchangeRate)` would let a later
 * call site quietly treat "500 leaves" as "500 of something spendable" without a single line of
 * review ever seeing that leap happen.
 *
 * The difference from `Points` is what a Lístek CAN do: it is redeemable, against the reviewed
 * [BenefitCatalog], for a benefit the bank itself delivers. That redemption is a burn against this
 * ledger plus a grant record — never a payment, never a transfer to another party outside the one
 * household path ADR-0282 D9 defines, and never a price quoted in any currency. Those four
 * absences are what keep a Lístek outside the definition of electronic money; they are properties
 * of this type and of [LeafLedgerEntry], not policy asserted in prose.
 *
 * `value class` for a zero-allocation opaque wrapper, with a private constructor so every non-test
 * call site goes through [of] and its non-negative invariant.
 */
@JvmInline
value class Leaves private constructor(val value: Int) : Comparable<Leaves> {

    operator fun plus(other: Leaves): Leaves = of(value + other.value)

    /**
     * Subtraction that cannot go negative — an attempt to burn more than a lot holds is a
     * programming error here, not a runtime state, because [LeafLedger] resolves the lots first.
     */
    operator fun minus(other: Leaves): Leaves = of(value - other.value)

    override fun compareTo(other: Leaves): Int = value.compareTo(other.value)

    fun isZero(): Boolean = value == 0

    override fun toString(): String = "$value leaves"

    companion object {
        val ZERO: Leaves = Leaves(0)

        fun of(value: Int): Leaves {
            require(value >= 0) { "leaves must be non-negative, was $value" }
            return Leaves(value)
        }
    }
}
