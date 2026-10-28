// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.delegation.domain.model.CountedSpend
import java.math.BigDecimal
import java.util.UUID

/**
 * Simulated delegated-spend state (ADR-0249 D3): every spend reservation the scenario drove
 * through the real `SpendCeilings.evaluate` policy and every ledger journal that actually
 * landed for a CONFIRMED reservation, keyed per grant so the Layer-3 invariants can assert:
 *
 *  1. *completeness* — no reservation may still be RESERVED once the step has settled
 *     (the scenario confirms or releases every reservation it creates, in the same step);
 *  2. *conservation* — Σ CONFIRMED reservation amounts == Σ ledger journals posted for
 *     delegated spend on that grant (a confirmed reservation whose money never posted, or a
 *     posted journal with no confirmed reservation, is caught per grant);
 *  3. *ceiling integrity* — every reservation decision recorded the exact `CountedSpend` the
 *     real policy was asked with; the invariant replays `counted + amount <= ceiling` against
 *     the grant's real limits, so a scenario that reserved against a stale or invented
 *     counted value fails here even when the money side still conserves.
 *
 * A REFUSED decision (the real `SpendDecision.Refused`) is recorded too: it proves the
 * ceiling was actually consulted, and the invariant replays it the same way — a refusal
 * must remain a refusal under the recorded counted value.
 */
class DelegationSpendBook {
    /** One reservation decision as the real policy saw it. */
    data class Decision(
        val grantId: UUID,
        val reservationId: UUID,
        val amount: BigDecimal,
        val counted: CountedSpend,
        val refused: Boolean,
    )

    private val decisions = mutableListOf<Decision>()
    private val confirmed = mutableMapOf<UUID, BigDecimal>()
    private val posted = mutableMapOf<UUID, BigDecimal>()

    /** Record the policy decision for one reservation attempt (allowed or refused). */
    fun recordDecision(decision: Decision) {
        decisions += decision
    }

    /** Record a reservation that reached CONFIRMED (its money moved this step). */
    fun recordConfirmed(grantId: UUID, amount: BigDecimal) {
        confirmed[grantId] = confirmed.getOrDefault(grantId, BigDecimal.ZERO) + amount
    }

    /** Record the ledger journal actually posted for one confirmed delegated spend. */
    fun recordPosted(grantId: UUID, amount: BigDecimal) {
        posted[grantId] = posted.getOrDefault(grantId, BigDecimal.ZERO) + amount
    }

    /** Every grant that saw a decision on either side — the invariant checks the union. */
    fun grantIds(): Set<UUID> = decisions.map { it.grantId }.toSet() + confirmed.keys + posted.keys

    fun decisionsFor(grantId: UUID): List<Decision> = decisions.filter { it.grantId == grantId }

    fun confirmedAmount(grantId: UUID): BigDecimal = confirmed.getOrDefault(grantId, BigDecimal.ZERO)

    fun postedAmount(grantId: UUID): BigDecimal = posted.getOrDefault(grantId, BigDecimal.ZERO)
}
