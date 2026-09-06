// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

/**
 * ADR-0282 D5 / ADR-0220 D3 rule 4 — "reward economics are capped per customer per year". The cap
 * is a domain invariant here rather than a configuration value a deploy can widen quietly.
 *
 * The decision type is a sealed hierarchy, not a Boolean, and that is the load-bearing choice.
 * This repository has already paid for the alternative once: `PushResult.skipped()` carried
 * `success = true`, so every push in an environment with no APNs credentials was counted as
 * delivered and the row committed `SENT`. A capped earn and a granted earn are different
 * outcomes; sharing a signal between them would make "the programme is refusing to award" look
 * exactly like "the programme is awarding", which is the one state nobody would think to alert on.
 */
object AnnualCap {
    /** Leaves a single party may earn within one calendar year. */
    val PER_PARTY_PER_YEAR: Leaves = Leaves.of(CAP_LEAVES)

    /**
     * Decides whether [requested] may be awarded on top of [earnedThisYear].
     *
     * Deliberately all-or-nothing: a partial award would need its own provisioning, its own
     * customer-facing explanation and its own reversal semantics, and none of those are decided
     * yet. Refusing the whole award is the honest behaviour, and [Decision.Capped] carries the
     * headroom so a caller can say why.
     */
    fun evaluate(earnedThisYear: Leaves, requested: Leaves): Decision {
        val remaining = if (earnedThisYear >= PER_PARTY_PER_YEAR) {
            Leaves.ZERO
        } else {
            PER_PARTY_PER_YEAR - earnedThisYear
        }
        return if (requested <= remaining) Decision.Grant else Decision.Capped(remaining)
    }

    sealed class Decision {
        /** The full requested amount fits under the cap. */
        object Grant : Decision()

        /** Nothing is awarded. [remaining] is the headroom that was left, for the explanation. */
        data class Capped(val remaining: Leaves) : Decision()
    }

    private const val CAP_LEAVES = 5000
}
