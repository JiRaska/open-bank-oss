// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.risk.integration

import com.openbank.risk.application.port.out.SnapshotRepository
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Regression coverage for the fleet's #2148/#2187 lesson, applied to the new
 * [com.openbank.risk.infrastructure.schedule.EodSnapshotScheduler]: a plain (non-`suspend`)
 * `@Scheduled` method carries no Vert.x context, so calling the scheduler bean's method DIRECTLY
 * from a test proves nothing about whether the real, framework-dispatched tick reaches its use
 * case at all. This test drives the REAL cron (shrunk to every two seconds) against a real
 * Postgres and asserts a stored snapshot row actually appears — the same shape as
 * `LedgerSchedulerVertxContextIT` in openbank-ledger-service.
 *
 * The **mandatory negative check** for this IT (see the PR description for the exact evidence):
 * commenting out the `snapshotUseCase.createSnapshot(asOf)` call in
 * `EodSnapshotScheduler.runOnce` (turning the tick into a no-op that logs and returns) made this
 * test fail — proving it actually detects the absence of behaviour, not just presence of a green
 * run. Reverting restores the passing test.
 */
@QuarkusTest
@QuarkusTestResource(EodSnapshotSchedulerVertxContextIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(EodSnapshotSchedulerVertxContextIT.FastEodSnapshotProfile::class)
class EodSnapshotSchedulerVertxContextIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory("fx-fixing-in")

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * Enables the scheduler and shrinks its cron to every two seconds — a LITERAL, not a derived
     * or randomised value: `QuarkusTestProfile` loads in its own classloader, so a companion-object
     * value computed at class-init time can disagree between what the profile hands the scheduler
     * and what a test asserts against (CLAUDE.md's `StandingOrderExecutionSweepIT` /
     * `LedgerSchedulerVertxContextIT` classloader-duplication note).
     */
    class FastEodSnapshotProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.risk.eod-snapshot.enabled" to "true",
            "openbank.risk.eod-snapshot.cron" to "*/2 * * * * ?",
        )
    }

    @Inject
    lateinit var snapshotRepository: SnapshotRepository

    @Inject
    lateinit var ledger: FakeLedgerPort

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
    fun `the scheduled EOD snapshot records a run for today's Prague business date`() {
        ledger.inputs = Fixtures.tiedOut()
        val expectedAsOf = ZonedDateTime.now(ZoneId.of("Europe/Prague")).toLocalDate()

        val recorded = await {
            onEventLoop { snapshotRepository.listRecent(10) }.any { it.asOf == expectedAsOf }
        }

        assertThat(recorded)
            .describedAs(
                "a scheduler-dispatched EOD snapshot must record a run for today ($expectedAsOf) " +
                    "— never recording one means the tick did nothing (either it never reached " +
                    "its use case off the Vert.x context, #2148/#2187, or the scheduler was " +
                    "silently disabled)",
            )
            .isTrue()

        val stored = onEventLoop { snapshotRepository.listRecent(10) }.first { it.asOf == expectedAsOf }
        assertThat(stored.requestedBy)
            .describedAs(
                "a scheduler-dispatched run must record the scheduler's own requester constant, " +
                    "not null and not an operator's principal",
            )
            .isEqualTo("system:risk-engine-eod-snapshot")
    }

    @Test
    fun `a second tick after the first replays instead of duplicating`() {
        ledger.inputs = Fixtures.tiedOut()
        val expectedAsOf = ZonedDateTime.now(ZoneId.of("Europe/Prague")).toLocalDate()

        // Let at least two ticks (every 2s) pass.
        await { onEventLoop { snapshotRepository.listRecent(10) }.any { it.asOf == expectedAsOf } }
        Thread.sleep(EXTRA_SETTLE_MILLIS)

        val runsForToday = onEventLoop { snapshotRepository.listRecent(50) }.filter { it.asOf == expectedAsOf }

        assertThat(runsForToday)
            .describedAs(
                "createSnapshot is keyed on (asOf, inputHash) — repeated ticks against unchanged " +
                    "ledger inputs for the same business day must replay the same row, never " +
                    "create a second one",
            )
            .hasSize(1)
    }

    private companion object {
        /** Generous vs the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val EXTRA_SETTLE_MILLIS = 4_500L
    }
}
