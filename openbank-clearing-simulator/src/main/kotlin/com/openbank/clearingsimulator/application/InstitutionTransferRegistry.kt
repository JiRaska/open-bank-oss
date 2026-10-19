// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.application

/**
 * What the simulated scheme has already taken, keyed by UETR — the deduplication a real RTGS /
 * FI network performs on its tracking reference. The same UETR submitted twice gets the SAME
 * pacs.002 back and settles once; the rail resolves an unanswered submission by asking for it
 * ([statusOf]) instead of resending. In memory and bounded (oldest evicted beyond [capacity]):
 * the simulator is a non-production counterparty and persists nothing.
 */
class InstitutionTransferRegistry(private val capacity: Int = DEFAULT_CAPACITY) {
    private val byUetr = object : LinkedHashMap<String, ClearingResult>(INITIAL, LOAD) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClearingResult>?) = size > capacity
    }
    private var settled = 0L

    /** The recorded result for [uetr], or the one [decide] produces — recorded and counted once. */
    @Synchronized
    fun recordOnce(uetr: String, decide: () -> ClearingResult): ClearingResult = byUetr[uetr] ?: decide().also {
        byUetr[uetr] = it
        if (it.settled) settled++
    }

    @Synchronized
    fun statusOf(uetr: String): ClearingResult? = byUetr[uetr]

    /** How many distinct transfers settled — what "exactly one settlement" is measured by. */
    @Synchronized
    fun settledCount(): Long = settled

    private companion object {
        const val DEFAULT_CAPACITY = 100_000
        const val INITIAL = 1024
        const val LOAD = 0.75f
    }
}
