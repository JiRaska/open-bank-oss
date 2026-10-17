// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.CloseParticipationUseCase
import com.openbank.ledger.application.port.`in`.ReportParticipationCommand
import com.openbank.ledger.application.port.`in`.WaiveParticipationCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.application.usecase.CloseParticipantForbiddenException
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.ParticipationOutcome
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
 * ADR-0342 D3 end to end: a CUTOFF day whose tie-out is OK still does not tie out while a declared
 * day-close participant (interest) has not reported — and does once it reports, or once an operator
 * waives it.
 *
 * Drives the REAL accounting-day cron (two seconds, real Postgres) in ENFORCE mode, for the same
 * reason as [AccountingDayCutoffRecheckIT]: a direct call would supply the Vert.x context the
 * scheduler does not. Each held day is paired with a control day that differs ONLY by the interest
 * report, so "stays CUTOFF" is observed after the cron demonstrably ran — never as a vacuous absence.
 * Dates are in 2019 so they cannot collide with any day the scheduler opens itself.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DayCloseParticipantsIT.EnforcingProfile::class)
class DayCloseParticipantsIT {

    class EnforcingProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.ledger.tieout.cron" to "*/2 * * * * ?",
            "openbank.ledger.accounting-day.cron" to "*/2 * * * * ?",
            "openbank.ledger.fx-revaluation.cron" to "off",
            "openbank.outbox.dispatch-enabled" to "false",
            "openbank.ledger.close.participants-mode" to "enforce",
            "openbank.ledger.close.participants.interest.principal" to INTEREST_PRINCIPAL,
        )
    }

    @Inject
    lateinit var tieOutRuns: TieOutRunRepository

    @Inject
    lateinit var accountingDays: AccountingDayRepository

    @Inject
    lateinit var participation: CloseParticipationUseCase

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

    @BeforeEach
    fun schedulerAnchored() {
        val anchored = await { onEventLoop { accountingDays.findByDate(accountingClock.today()) } != null }
        assertThat(anchored).describedAs("the scheduler must open today before 2019 rows are seeded").isTrue()
    }

    /** A CUTOFF day with an OK tie-out recorded after its cutoff — everything but participants. */
    private fun seedTiedOutEligibleDay(date: LocalDate) {
        val cutoffAt = Instant.now().minus(Duration.ofHours(2))
        onEventLoop {
            tieOutRuns.save(
                TieOutRunRecord(
                    id = UUID.randomUUID(),
                    asOf = date,
                    runAt = cutoffAt.plus(Duration.ofMinutes(30)),
                    status = TieOutRunStatus.OK,
                    accountsChecked = 4,
                    breaks = 0,
                    errors = 0,
                ),
            )
            val opened = AccountingDayRecord.open(date, cutoffAt.minus(Duration.ofDays(1)), SEED_ACTOR)
            val cut = opened.transitionTo(AccountingDayStatus.CUTOFF, SEED_ACTOR, cutoffAt)
            accountingDays.saveOpened(
                cut,
                OutboxMessage(aggregateId = cut.id, eventType = "AccountingDayTransitioned", payload = "{}"),
            )
        }
    }

    private fun interestReports(date: LocalDate, outcome: ParticipationOutcome, journals: Int, reason: String? = null) =
        onEventLoop {
            participation.report(
                ReportParticipationCommand(
                    businessDate = date,
                    participant = "interest",
                    outcome = outcome,
                    journalsPosted = journals,
                    itemsProcessed = journals,
                    reason = reason,
                    callerPrincipal = INTEREST_PRINCIPAL,
                ),
            )
        }

    private fun statusOf(date: LocalDate) = onEventLoop { accountingDays.findByDate(date) }?.status

    @Test
    fun `a day with interest late stays CUTOFF while an otherwise identical reported day ties out`() {
        seedTiedOutEligibleDay(CONTROL_DAY)
        interestReports(CONTROL_DAY, ParticipationOutcome.RAN, journals = 3)
        seedTiedOutEligibleDay(LATE_DAY)

        assertThat(await { statusOf(CONTROL_DAY) == AccountingDayStatus.TIED_OUT })
            .describedAs("the control day proves the cron ran and the tie-out condition is met")
            .isTrue()
        assertThat(statusOf(LATE_DAY))
            .describedAs("interest has not reported — the close must be blocked, not passed")
            .isEqualTo(AccountingDayStatus.CUTOFF)
        assertThat(onEventLoop { participation.completeness(LATE_DAY) }.missing).containsExactly("interest")

        // Interest runs late and reports; the next tick closes the day.
        interestReports(LATE_DAY, ParticipationOutcome.RAN, journals = 2)
        assertThat(await { statusOf(LATE_DAY) == AccountingDayStatus.TIED_OUT }).isTrue()
    }

    @Test
    fun `an operator waiver releases a held day and is recorded with the operator and reason`() {
        seedTiedOutEligibleDay(WAIVED_DAY)
        onEventLoop {
            participation.waive(
                WaiveParticipationCommand(WAIVED_DAY, "interest", "interest-service outage, INC-1", "operator-sub"),
            )
        }

        assertThat(await { statusOf(WAIVED_DAY) == AccountingDayStatus.TIED_OUT }).isTrue()
        val waived = onEventLoop { participation.completeness(WAIVED_DAY) }.reported.getValue("interest")
        assertThat(waived.outcome).isEqualTo(ParticipationOutcome.WAIVED)
        assertThat(waived.reportedBy).isEqualTo("operator-sub")
    }

    @Test
    fun `a REFUSED participant holds the day exactly like a missing one until an operator waives it`() {
        seedTiedOutEligibleDay(REFUSED_CONTROL_DAY)
        interestReports(REFUSED_CONTROL_DAY, ParticipationOutcome.RAN, journals = 1)
        seedTiedOutEligibleDay(REFUSED_DAY)
        interestReports(REFUSED_DAY, ParticipationOutcome.REFUSED, journals = 0, reason = "3 posting(s) refused")

        assertThat(await { statusOf(REFUSED_CONTROL_DAY) == AccountingDayStatus.TIED_OUT })
            .describedAs("the control day proves the cron ran and the tie-out condition is met")
            .isTrue()
        assertThat(statusOf(REFUSED_DAY))
            .describedAs("interest was refused - its interest was not posted, so the day must not tie out")
            .isEqualTo(AccountingDayStatus.CUTOFF)
        val held = onEventLoop { participation.completeness(REFUSED_DAY) }
        assertThat(held.refused).containsExactly("interest")
        assertThat(held.complete).isFalse()

        onEventLoop {
            participation.waive(WaiveParticipationCommand(REFUSED_DAY, "interest", "accepted by finance, INC-2", "op"))
        }
        assertThat(await { statusOf(REFUSED_DAY) == AccountingDayStatus.TIED_OUT }).isTrue()
    }

    @Test
    fun `a report after the day tied out is stored as late and a foreign principal is refused`() {
        seedTiedOutEligibleDay(LATE_REPORT_DAY)
        interestReports(LATE_REPORT_DAY, ParticipationOutcome.RAN, journals = 1)
        assertThat(await { statusOf(LATE_REPORT_DAY) == AccountingDayStatus.TIED_OUT }).isTrue()

        val late = interestReports(LATE_REPORT_DAY, ParticipationOutcome.REFUSED, journals = 0, reason = "day closed")
        assertThat(late.late).isTrue()
        assertThat(statusOf(LATE_REPORT_DAY)).isEqualTo(AccountingDayStatus.TIED_OUT)

        assertThatThrownBy {
            onEventLoop {
                participation.report(
                    ReportParticipationCommand(
                        LATE_REPORT_DAY,
                        "interest",
                        ParticipationOutcome.RAN,
                        0,
                        0,
                        null,
                        callerPrincipal = "service-account-openbank-billing",
                    ),
                )
            }
        }.isInstanceOf(CloseParticipantForbiddenException::class.java)
    }

    private companion object {
        const val INTEREST_PRINCIPAL = "service-account-openbank-interest"
        val CONTROL_DAY: LocalDate = LocalDate.of(2019, 1, 10)
        val LATE_DAY: LocalDate = LocalDate.of(2019, 1, 11)
        val WAIVED_DAY: LocalDate = LocalDate.of(2019, 2, 10)
        val LATE_REPORT_DAY: LocalDate = LocalDate.of(2019, 3, 10)
        val REFUSED_CONTROL_DAY: LocalDate = LocalDate.of(2019, 4, 9)
        val REFUSED_DAY: LocalDate = LocalDate.of(2019, 4, 10)
        const val SEED_ACTOR = "it-seed"
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
