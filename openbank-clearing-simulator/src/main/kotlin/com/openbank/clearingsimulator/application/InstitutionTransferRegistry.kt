// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.application

import java.time.Instant

/**
 * What the simulated scheme has already taken, keyed by UETR — the deduplication a real RTGS /
 * FI network performs on its tracking reference. The same UETR submitted twice gets the SAME
 * pacs.002 back and settles once; the rail resolves an unanswered submission by asking for it
 * ([statusOf]) instead of resending.
 *
 * A transfer can also be taken for DEFERRED settlement: answered `ACSP` at submission and settled
 * (`ACSC`) once its settlement time passes — the asynchronous settlement a real RTGS performs
 * after acceptance. The settlement happens on the scheme's clock, observed by the next status
 * query at or after [Recorded.settleAt]; it is counted once, when it happens.
 *
 * In memory and bounded (oldest evicted beyond [capacity]): the simulator is a non-production
 * counterparty and persists nothing.
 */
class InstitutionTransferRegistry(private val capacity: Int = DEFAULT_CAPACITY) {
    /** [initial] as answered; [later] replaces it at [settleAt] (deferred settlement), or null. */
    data class Recorded(val initial: ClearingResult, val later: ClearingResult? = null, val settleAt: Instant? = null)

    private val byUetr = object : LinkedHashMap<String, Recorded>(INITIAL, LOAD) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Recorded>?) = size > capacity
    }
    private var settled = 0L

    /** The recorded answer for [uetr], or the one [decide] produces — recorded and counted once. */
    @Synchronized
    fun recordOnce(uetr: String, now: Instant, decide: () -> Recorded): ClearingResult {
        byUetr[uetr]?.let { return current(uetr, it, now) }
        val recorded = decide()
        byUetr[uetr] = recorded
        if (recorded.initial.settled) settled++
        return recorded.initial
    }

    /** What the scheme holds for [uetr] at [now]; a deferred settlement whose time came is applied. */
    @Synchronized
    fun statusOf(uetr: String, now: Instant = Instant.now()): ClearingResult? =
        byUetr[uetr]?.let { current(uetr, it, now) }

    private fun current(uetr: String, r: Recorded, now: Instant): ClearingResult {
        val later = r.later ?: return r.initial
        if (now.isBefore(r.settleAt)) return r.initial
        byUetr[uetr] = Recorded(later)
        if (later.settled) settled++
        return later
    }

    /** How many distinct transfers settled — what "exactly one settlement" is measured by. */
    @Synchronized
    fun settledCount(): Long = settled

    private companion object {
        const val DEFAULT_CAPACITY = 100_000
        const val INITIAL = 1024
        const val LOAD = 0.75f
    }
}
