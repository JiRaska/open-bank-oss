// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.application.port.out.WithdrawalProposalRepository
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.account.it.PostgresRedpandaRedisTestResource
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Proves the expiry sweep actually RUNS when the scheduler dispatches it.
 *
 * A plain (non-`suspend`) `@Scheduled` method carries no Vert.x context — Quarkus invokes it on a
 * bare executor-thread — so a body wrapping the reactive Panache repository in `runBlocking` throws
 * `HR000068` and the tick aborts having done nothing, silently. Five schedulers in this fleet, three
 * of them money-path, had never once run for exactly that reason (#2148, #2187).
 *
 * **Why this drives the cron instead of calling `sweep()`.** The defect is in how the framework
 * invokes the method, not in the method body: calling `sweep()` from a test supplies the very Vert.x
 * context the real scheduler does not, so such a test passes against broken code and proves nothing.
 * The profile below shrinks the cron to every two seconds against a real Postgres and the test waits
 * for a genuinely scheduler-dispatched run to leave its mark.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@TestProfile(SavingsProposalExpirySchedulerVertxContextIT.FastExpiryProfile::class)
class SavingsProposalExpirySchedulerVertxContextIT {

    /**
     * Fires the sweep every two seconds instead of every ten minutes. Literals only — a
     * `QuarkusTestProfile` loads in a different classloader from the test class, so a randomized
     * value here would hand the scheduler one thing and the assertion another.
     */
    class FastExpiryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.savings.proposal-expiry-cron" to "*/2 * * * * ?",
            // Off so a dispatched event cannot be re-consumed in this same JVM mid-assertion.
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var proposals: WithdrawalProposalRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    @Test
    fun `a scheduler-dispatched sweep expires a closed-window proposal`() {
        val now = OffsetDateTime.now()
        val stale = WithdrawalProposal(
            id = Ids.newId(),
            accountId = UUID.randomUUID(),
            delegatePartyId = UUID.randomUUID(),
            amountMinor = 150_000,
            currency = "CZK",
            createdAt = now.minusDays(30),
            expiresAt = now.minusDays(23),
        )
        onEventLoop { proposals.save(stale) }

        val expired = await {
            onEventLoop { proposals.findById(stale.id) }?.status == WithdrawalProposalStatus.EXPIRED
        }

        assertThat(expired)
            .describedAs(
                "a scheduler-dispatched expiry sweep must flip a closed-window proposal to EXPIRED " +
                    "— it never doing so means the tick threw HR000068 off the Vert.x context " +
                    "before the first query, the #2148/#2187 failure mode",
            )
            .isTrue()
    }

    @Test
    fun `the sweep leaves an open-window proposal alone`() {
        val now = OffsetDateTime.now()
        val fresh = WithdrawalProposal(
            id = Ids.newId(),
            accountId = UUID.randomUUID(),
            delegatePartyId = UUID.randomUUID(),
            amountMinor = 150_000,
            currency = "CZK",
            createdAt = now,
            expiresAt = now.plusDays(7),
        )
        onEventLoop { proposals.save(fresh) }

        // Long enough that several ticks have certainly fired.
        Thread.sleep(SETTLE_MILLIS)

        assertThat(onEventLoop { proposals.findById(fresh.id) }?.status)
            .describedAs("a proposal inside its window must survive the sweep")
            .isEqualTo(WithdrawalProposalStatus.PENDING)
    }

    private companion object {
        /** Generous vs the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val SETTLE_MILLIS = 6_000L
    }
}
