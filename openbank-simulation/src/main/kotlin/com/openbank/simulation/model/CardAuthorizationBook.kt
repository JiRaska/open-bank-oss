// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.cardprocessing.domain.model.CardAuthorization
import java.util.UUID

/**
 * Simulated card-authorization hold state: the REAL `openbank-card-processing-service`
 * `CardAuthorization` aggregate per authorization, plus the two facts the DST invariant needs
 * that the aggregate itself does not carry:
 *
 * - `postedMinorUnits` — how much of the cleared total actually landed as ledger journals
 *   (posted by [com.openbank.simulation.scenario.CardAuthorizationScenario]), so
 *   `MoneyPathInvariants.cardAuthorizationConservation` can assert *Σ cleared == Σ posted* per
 *   authorization — a clearing the policy accepted but the ledger never received (or received
 *   twice, on a replayed presentment) is exactly the defect class this catches;
 * - `releasedMinorUnits` — how much of the authorized amount the scenario released back from
 *   the balance reservation on reversal/expiry, so the invariant can assert
 *   *cleared + released == authorized* — a hold that is neither fully cleared nor fully
 *   released has silently frozen customer money, and a release that overshoots frees money
 *   the scheme can still present against.
 *
 * The aggregate's own `heldAmountMinorUnits` is deliberately NOT re-stored here: it is a
 * derived value, and a second stored copy is the drift the production KDoc warns about.
 */
class CardAuthorizationBook {

    private class Entry(var authorization: CardAuthorization, var postedMinorUnits: Long, var releasedMinorUnits: Long)

    private val entries = mutableMapOf<UUID, Entry>()
    fun place(authorization: CardAuthorization) {
        entries[authorization.id] = Entry(
            authorization = authorization,
            postedMinorUnits = 0,
            releasedMinorUnits = 0,
        )
    }

    /** Store the aggregate after a domain transition (clear/reverse/expire). */
    fun replace(authorization: CardAuthorization) {
        val entry = entries[authorization.id] ?: error("unknown authorization ${authorization.id}")
        entry.authorization = authorization
    }

    /** Record one capture journal posted for [authorizationId] (called after the post is durable). */
    fun recordPosted(authorizationId: UUID, amountMinorUnits: Long) {
        val entry = entries[authorizationId] ?: error("unknown authorization $authorizationId")
        entry.postedMinorUnits += amountMinorUnits
    }

    /** Record one hold release of [amountMinorUnits] back to available balance. */
    fun recordReleased(authorizationId: UUID, amountMinorUnits: Long) {
        val entry = entries[authorizationId] ?: error("unknown authorization $authorizationId")
        entry.releasedMinorUnits += amountMinorUnits
    }

    fun get(authorizationId: UUID): CardAuthorization? = entries[authorizationId]?.authorization

    fun all(): Collection<CardAuthorization> = entries.values.map { it.authorization }

    fun postedMinorUnits(authorizationId: UUID): Long = entries[authorizationId]?.postedMinorUnits ?: 0L

    fun releasedMinorUnits(authorizationId: UUID): Long = entries[authorizationId]?.releasedMinorUnits ?: 0L
}
