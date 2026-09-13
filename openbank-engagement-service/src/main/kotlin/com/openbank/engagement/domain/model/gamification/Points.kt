// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.model.gamification

/**
 * ADR-0220 D3's reward counter. Deliberately an opaque, non-negative integer counter — NOT a
 * currency-typed amount, and carrying no arithmetic, comparison or conversion toward any monetary
 * type in this codebase (`Money`, `MinorUnits`, an `openbank-ledger-service` amount, or any of
 * their equivalents in `openbank-libs-domain`). This is the D3.4 boundary made structural: "Reward
 * economics are capped per customer per year and provisioned through the billing path (ADR-0143),
 * never marketing cash" already forbids Points from *being* money; giving [Points] a `toMoney()`
 * or an `operator times(exchangeRate)` would let a later call site quietly treat "500 points" as
 * "500 of something spendable" without a single line of review ever seeing that leap happen. The
 * conversion, when a business ever wants one, belongs entirely outside the domain layer, as an
 * explicit, reviewed, and independently rate-limited call into the ADR-0143 billing path — never a
 * method on this type.
 *
 * `value class` for a zero-allocation opaque wrapper (same convention as `com.openbank.libs`'s
 * other identifier/amount wrappers) — the private constructor plus [of] forces every non-test call
 * site through the non-negative invariant; there is no public constructor to bypass it.
 */
@JvmInline
value class Points private constructor(val value: Int) {

    operator fun plus(other: Points): Points = Points(value + other.value)

    operator fun compareTo(other: Points): Int = value.compareTo(other.value)

    companion object {
        val ZERO: Points = Points(0)

        fun of(value: Int): Points {
            require(value >= 0) { "points must be non-negative, was $value" }
            return Points(value)
        }
    }
}
