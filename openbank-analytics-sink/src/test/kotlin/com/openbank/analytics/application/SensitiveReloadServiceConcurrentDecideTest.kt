// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.openbank.analytics.infrastructure.proposal.InMemoryProposalStore
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.IngestSource
import com.openbank.libs.analytics.ProposalState
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The four-eyes decision on an analytics reload proposal (ADR-0023, F3) must be applied AT MOST
 * ONCE, mirroring `openbank-lending-service`'s `CompliancePackConcurrentDecideIT` for the
 * compliance-pack four-eyes flow.
 *
 * WHY THIS IS NOT COVERED BY [RecoveryFlowsTest]
 *
 * That test drives propose -> approve -> execute sequentially, so [SensitiveReloadService]'s
 * `require(id)` always sees the committed result of the previous call and any bug in the
 * read-then-write window is invisible. Before [ProposalStore.claim] existed, `approve`/`reject`
 * read the proposal, decided against that in-memory snapshot, and wrote back — two decisions
 * arriving together would both observe `PROPOSED`, both pass [com.openbank.libs.analytics.Proposal]'s
 * own state check, and both write: a lost update on a segregation-of-duties control.
 *
 * WHAT THIS TEST DOES
 *
 * It races a real `approve` against a real `reject` on the same proposal, over real concurrent
 * threads, [ROUNDS] times with a fresh proposal each round, and asserts that exactly one of the two
 * ever succeeds, with the persisted state agreeing with whichever one won. Runs against the
 * in-memory store (the default, offline-buildable binding) — no container needed, since the
 * compare-and-set primitive under test ([InMemoryProposalStore.claim]) is a plain JVM data
 * structure, the same one every binding of [com.openbank.analytics.application.port.out.ProposalStore]
 * uses for the phase-claim gate (see [com.openbank.analytics.infrastructure.proposal.ClickHouseProposalStore]'s
 * KDoc for why the ClickHouse binding's claim is scoped the same way).
 *
 * Like the lending test, this is a timing test and is honest about being probabilistic: it can only
 * fail when the race actually interleaves, so it runs [ROUNDS] rounds rather than trusting one. It
 * cannot produce a false RED — two accepted decisions on one proposal is a defect however the
 * threads landed.
 */
class SensitiveReloadServiceConcurrentDecideTest {

    private companion object {
        const val ROUNDS = 20
        const val BARRIER_TIMEOUT_SECONDS = 10L
    }

    private fun newService(): SensitiveReloadService = SensitiveReloadService().apply {
        store = InMemoryProposalStore()
        clock = Clock.systemUTC()
        backfill = BackfillService()
    }

    @Test
    fun `a concurrent approve and reject cannot both be applied to one proposal`() {
        val service = newService()
        val pool = Executors.newFixedThreadPool(2)
        val violations = mutableListOf<String>()
        try {
            repeat(ROUNDS) { round ->
                val proposal = runBlocking {
                    service.propose(
                        BackfillRequest(
                            source = IngestSource.CORRECTION,
                            from = Instant.parse("2026-01-01T00:00:00Z"),
                            to = Instant.parse("2026-01-02T00:00:00Z"),
                            reason = "race probe $round",
                            requestedBy = "alice",
                        ),
                    )
                }
                val barrier = CyclicBarrier(2)

                val approve = pool.submit(
                    decideTask(barrier) {
                        runBlocking { service.approve(proposal.id, "bob", "race") }
                    },
                )
                val reject = pool.submit(
                    decideTask(barrier) { runBlocking { service.reject(proposal.id, "carol", "race") } },
                )
                val outcomes = listOf(approve.get(), reject.get())

                val wins = outcomes.count { it }
                if (wins != 1) {
                    violations += "proposal ${proposal.id}: expected exactly 1 winner, got $wins (outcomes=$outcomes)"
                }

                val state = runBlocking { service.get(proposal.id) }?.state
                val expected = when {
                    outcomes[0] -> ProposalState.APPROVED
                    outcomes[1] -> ProposalState.REJECTED
                    else -> null
                }
                if (expected != null && state != expected) {
                    violations += "proposal ${proposal.id}: winner implies $expected but stored state is $state"
                }
            }
        } finally {
            pool.shutdownNow()
        }

        assertThat(violations)
            .describedAs("four-eyes decisions applied more than once, or a stored state disagreeing with the winner")
            .isEmpty()
    }

    private fun decideTask(barrier: CyclicBarrier, decide: () -> Unit) = Callable {
        barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        runCatching(decide).isSuccess
    }
}
