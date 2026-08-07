// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.FxRevaluationResult
import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.it.PostgresTestResource
import com.openbank.libs.domain.calendar.AccountingClock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for #2187 (the fleet sweep of #2148) — both of ledger-service's daily
 * `@Scheduled` jobs silently did nothing on every single tick.
 *
 * [com.openbank.ledger.infrastructure.schedule.TieOutScheduler.runTieOut] and
 * [com.openbank.ledger.infrastructure.schedule.FxRevaluationScheduler.revalueDaily] were plain
 * (non-`suspend`) methods whose bodies were `runBlocking { … }`. Quarkus invokes such a method on a
 * bare `executor-thread`, which carries **no Vert.x context**, so the first reactive call — in both
 * cases `PostgresClusterLock`'s `Panache.withTransaction`, which sits *outside* every try/catch in
 * either scheduler — threw `HR000068: This method should exclusively be invoked from a Vert.x
 * EventLoop thread` and aborted the whole tick. The sub-ledger tie-out control (ADR-0039 Phase B)
 * had therefore never recorded a run, and no FX position had ever been marked to the ČNB fixing.
 *
 * **Why this test drives the cron and not the methods directly.** The defect is in *how the
 * framework invokes the method*, not in the method body: calling `runTieOut()` from a test supplies
 * a Vert.x context the real scheduler does not, so such a test passes against the broken code and
 * proves nothing. The profile below shrinks both crons to every two seconds against a real Postgres
 * and the test waits for a genuinely scheduler-dispatched run to leave its mark.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(LedgerSchedulerVertxContextIT.FastSchedulerProfile::class)
class LedgerSchedulerVertxContextIT {

    /**
     * Fires both daily jobs every two seconds instead of at 06:00 / 15:00, and keeps the outbox
     * dispatcher off so a dispatched event cannot be re-consumed in this same JVM while the
     * assertions run.
     *
     * [RecordingFxRevaluationUseCase] is enabled only for this profile: the real
     * `FxRevaluationService` reaches out to fx-service for the ČNB fixing, which is not available
     * here — and it would be the wrong thing to assert anyway. What is under test is whether the
     * scheduler reaches its use case *at all*, which it did not.
     */
    class FastSchedulerProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // `%test.quarkus.scheduler.enabled` is `false` for this whole service (application.yaml)
            // — which is precisely why no ledger test could ever have caught #2187: with the
            // scheduler off, the only thing any test exercises is a direct call, and a direct call
            // supplies the Vert.x context the scheduler does not. This IT is the one place it is
            // deliberately switched back on.
            "quarkus.scheduler.enabled" to "true",
            "openbank.ledger.tieout.cron" to "*/2 * * * * ?",
            "openbank.ledger.fx-revaluation.cron" to "*/2 * * * * ?",
            "openbank.ledger.accounting-day.cron" to "*/2 * * * * ?",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> =
            mutableSetOf(RecordingFxRevaluationUseCase::class.java)
    }

    /** Records that the scheduler actually got as far as calling the use case. */
    @Alternative
    @ApplicationScoped
    class RecordingFxRevaluationUseCase : FxRevaluationUseCase {
        override suspend fun revalue(command: RevalueFxCommand): FxRevaluationResult {
            invocations.incrementAndGet()
            return FxRevaluationResult(command.date, posted = false, journalId = null, movements = emptyMap())
        }

        companion object {
            val invocations = AtomicInteger(0)
        }
    }

    @Inject
    lateinit var tieOutRuns: TieOutRunRepository

    @Inject
    lateinit var accountingDays: AccountingDayRepository

    @Inject
    lateinit var accountingClock: AccountingClock

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /** Polls [ready] until it holds, or the budget runs out. */
    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    @Test
    fun `the scheduled sub-ledger tie-out records a run`() {
        val recorded = await { onEventLoop { tieOutRuns.findLatest() } != null }

        assertThat(recorded)
            .describedAs(
                "a scheduler-dispatched tie-out must record a run — never recording one means the " +
                    "sweep threw HR000068 off the Vert.x context before the first query (#2187)",
            )
            .isTrue()
    }

    @Test
    fun `the scheduled accounting-day reconcile opens the current day`() {
        val opened = await {
            onEventLoop { accountingDays.findByDate(accountingClock.today()) } != null
        }

        assertThat(opened)
            .describedAs(
                "a scheduler-dispatched reconcile must open the current accounting day — the " +
                    "cluster lock it acquires first is a reactive Panache transaction, and off " +
                    "the Vert.x context it would throw HR000068 before the first query (#2187); " +
                    "measured live 2026-08-07: with nothing opening days, ledger_accounting_day " +
                    "had zero rows and the ADR-0207 day lock measured nothing",
            )
            .isTrue()
    }

    @Test
    fun `the scheduled FX revaluation reaches its use case`() {
        val reached = await { RecordingFxRevaluationUseCase.invocations.get() > 0 }

        assertThat(reached)
            .describedAs(
                "a scheduler-dispatched FX revaluation must reach the use case — the cluster lock " +
                    "it acquires first is a reactive Panache transaction, and off the Vert.x " +
                    "context it threw HR000068 before `revalue` was ever called (#2187)",
            )
            .isTrue()
    }

    private companion object {
        /** Generous vs the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
