// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.AccountingDayUseCase
import com.openbank.ledger.application.port.`in`.OpenAccountingDayCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.ledger.it.PostgresTestResource
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.persistence.outbox.OutboxMessage
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A CUTOFF day whose only tie-out run predates its cutoff must be re-checked and advance.
 *
 * Measured live (2026-09-30): accounting day 2026-07-31 was opened weeks late, cut off on
 * 2026-08-27, and its only tie-out run (OK) is from 2026-08-01 — before the cutoff, so
 * [com.openbank.ledger.infrastructure.schedule.AccountingDayScheduler] correctly refused it as
 * evidence, and [com.openbank.ledger.infrastructure.schedule.TieOutScheduler]'s forward-only
 * cursor never checked it again. It sat in CUTOFF indefinitely and held
 * `AccountingDayStuckInCutoff` firing, masking any other stuck day.
 *
 * Drives the REAL crons (two seconds, against a real Postgres) for the same reason as
 * [LedgerSchedulerVertxContextIT]: a direct call supplies the Vert.x context the scheduler does not.
 * Dates are in 2020 so they cannot collide with anything the schedulers open themselves.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(AccountingDayCutoffRecheckIT.FastSchedulerProfile::class)
class AccountingDayCutoffRecheckIT {

    class FastSchedulerProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.ledger.tieout.cron" to "*/2 * * * * ?",
            "openbank.ledger.accounting-day.cron" to "*/2 * * * * ?",
            // Reaches fx-service, which does not exist here and is not under test.
            "openbank.ledger.fx-revaluation.cron" to "off",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var tieOutRuns: TieOutRunRepository

    @Inject
    lateinit var accountingDays: AccountingDayRepository

    @Inject
    lateinit var accountingDayUseCase: AccountingDayUseCase

    @Inject
    lateinit var accountingClock: AccountingClock

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

    /**
     * Seed only once both schedulers have established their own anchors (today's day row, a
     * tie-out run for yesterday) — seeding a 2020 row first would make it the catch-up anchor.
     */
    @BeforeEach
    fun schedulersAnchored() {
        val yesterday = accountingClock.today().minusDays(1)
        val anchored = await {
            onEventLoop { accountingDays.findByDate(accountingClock.today()) } != null &&
                onEventLoop { tieOutRuns.findLatestFor(yesterday) } != null
        }
        assertThat(anchored).describedAs("schedulers must open today and check yesterday first").isTrue()
    }

    private fun seedCutoffDay(date: LocalDate, cutoffAt: Instant) = onEventLoop {
        val opened = AccountingDayRecord.open(date, cutoffAt.minus(Duration.ofDays(1)), SEED_ACTOR)
        accountingDays.saveOpened(opened, outbox(opened))
        val cut = opened.transitionTo(AccountingDayStatus.CUTOFF, SEED_ACTOR, cutoffAt)
        accountingDays.saveTransition(cut, opened.version, outbox(cut))
    }

    private fun seedRun(asOf: LocalDate, runAt: Instant, status: TieOutRunStatus) = onEventLoop {
        tieOutRuns.save(
            TieOutRunRecord(
                id = UUID.randomUUID(),
                asOf = asOf,
                runAt = runAt,
                status = status,
                accountsChecked = 4,
                breaks = if (status == TieOutRunStatus.BREAK) 1 else 0,
                errors = 0,
            ),
        )
    }

    private fun outbox(day: AccountingDayRecord) =
        OutboxMessage(aggregateId = day.id, eventType = "AccountingDayTransitioned", payload = "{}")

    private fun statusOf(date: LocalDate) = onEventLoop { accountingDays.findByDate(date) }?.status

    @Test
    fun `a CUTOFF day whose only OK run predates its cutoff is re-checked and advances`() {
        val cutoffAt = Instant.now().minus(Duration.ofHours(1))
        seedCutoffDay(STALE_OK_DAY, cutoffAt)
        seedRun(STALE_OK_DAY, cutoffAt.minus(Duration.ofHours(1)), TieOutRunStatus.OK)

        val advanced = await { statusOf(STALE_OK_DAY) == AccountingDayStatus.TIED_OUT }

        assertThat(advanced)
            .describedAs(
                "a CUTOFF day behind the tie-out cursor, whose only OK run predates its cutoff, " +
                    "must be re-checked and advance to TIED_OUT — before the fix it stayed CUTOFF forever",
            )
            .isTrue()
        val recheck = onEventLoop { tieOutRuns.findLatestFor(STALE_OK_DAY) }!!
        assertThat(recheck.runAt).isAfterOrEqualTo(cutoffAt)
        assertThat(recheck.status).isEqualTo(TieOutRunStatus.OK)

        // Forward catch-up unchanged: the re-check run is the newest by runAt, but it must not
        // drag the cursor back to 2020 — the day after the re-checked one is never walked.
        assertThat(onEventLoop { tieOutRuns.findLatest() }!!.asOf).isAfter(STALE_OK_DAY)
        assertThat(onEventLoop { tieOutRuns.findLatestFor(STALE_OK_DAY.plusDays(1)) }).isNull()
    }

    @Test
    fun `a CUTOFF day whose post-cutoff run found breaks stays CUTOFF and is not re-run`() {
        val cutoffAt = Instant.now().minus(Duration.ofHours(2))
        seedCutoffDay(BROKEN_DAY, cutoffAt)
        val breakRun = seedRun(BROKEN_DAY, cutoffAt.plus(Duration.ofMinutes(30)), TieOutRunStatus.BREAK)

        // A control day seeded alongside proves the ticks ran in the window we observed.
        val control = LocalDate.of(2020, 3, 1)
        seedCutoffDay(control, cutoffAt)
        seedRun(control, cutoffAt.minus(Duration.ofHours(1)), TieOutRunStatus.OK)
        assertThat(await { statusOf(control) == AccountingDayStatus.TIED_OUT }).isTrue()

        assertThat(statusOf(BROKEN_DAY)).isEqualTo(AccountingDayStatus.CUTOFF)
        assertThat(onEventLoop { tieOutRuns.findLatestFor(BROKEN_DAY) }!!.id)
            .describedAs("a day with a post-cutoff verdict is not re-checked again")
            .isEqualTo(breakRun.id)
    }

    @Test
    fun `opening a day earlier than the latest day on the calendar is refused`() {
        assertThatThrownBy {
            onEventLoop {
                accountingDayUseCase.open(OpenAccountingDayCommand(BACKDATED_DAY, openedBy = "it-operator"))
            }
        }.isInstanceOf(LedgerConflictException::class.java)
            .hasMessageContaining("forward only")
        assertThat(onEventLoop { accountingDays.findByDate(BACKDATED_DAY) }).isNull()
    }

    private companion object {
        val STALE_OK_DAY: LocalDate = LocalDate.of(2020, 1, 10)
        val BROKEN_DAY: LocalDate = LocalDate.of(2020, 2, 10)
        val BACKDATED_DAY: LocalDate = LocalDate.of(2020, 4, 10)
        const val SEED_ACTOR = "it-seed"
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
