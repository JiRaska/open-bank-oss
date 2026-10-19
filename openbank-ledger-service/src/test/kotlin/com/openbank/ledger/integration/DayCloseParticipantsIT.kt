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
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.ResultSet
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
            "authz.enforce" to "false",
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
    fun `a resent REFUSED after an operator waiver does not undo the waiver`() {
        seedTiedOutEligibleDay(WAIVER_RESEND_DAY)
        interestReports(WAIVER_RESEND_DAY, ParticipationOutcome.REFUSED, journals = 0, reason = "3 posting(s) refused")
        onEventLoop {
            participation.waive(WaiveParticipationCommand(WAIVER_RESEND_DAY, "interest", "accepted, INC-3", "op"))
        }

        // The reporter's per-process dedup cache resends the last days after a restart.
        val answer = interestReports(WAIVER_RESEND_DAY, ParticipationOutcome.REFUSED, journals = 0, reason = "resent")

        assertThat(answer.outcome).describedAs("the existing waiver is returned, nothing new is stored")
            .isEqualTo(ParticipationOutcome.WAIVED)
        val reported = onEventLoop { participation.completeness(WAIVER_RESEND_DAY) }.reported.getValue("interest")
        assertThat(reported.outcome).isEqualTo(ParticipationOutcome.WAIVED)
        assertThat(await { statusOf(WAIVER_RESEND_DAY) == AccountingDayStatus.TIED_OUT }).isTrue()
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

    /** Future cutoff instant keeps any cron verdict ineligible while the manual HTTP path is tested. */
    private fun seedManualDay(date: LocalDate) {
        onEventLoop {
            val now = Instant.now()
            val opened = AccountingDayRecord.open(date, now.minusSeconds(60), SEED_ACTOR)
            val cut = opened.transitionTo(AccountingDayStatus.CUTOFF, SEED_ACTOR, now.plus(Duration.ofHours(1)))
            accountingDays.saveOpened(
                cut,
                OutboxMessage(aggregateId = cut.id, eventType = "AccountingDayTransitioned", payload = "{}"),
            )
        }
    }

    @Test
    @TestSecurity(user = "manual-close-operator", roles = ["ROLE_OPERATOR"])
    fun `manual HTTP tie-out cannot bypass a missing participant in enforce mode`() {
        val date = LocalDate.of(2019, 6, 10)
        seedManualDay(date)
        given().contentType("application/json")
            .post("/api/v1/ledger/accounting-days/$date/transitions/TIED_OUT").then().statusCode(409)
        assertThat(statusOf(date)).isEqualTo(AccountingDayStatus.CUTOFF)
        assertThat(onEventLoop { participation.completeness(date) }.missing).containsExactly("interest")
    }

    @Test
    @TestSecurity(user = "manual-close-operator", roles = ["ROLE_OPERATOR"])
    fun `manual HTTP tie-out cannot bypass REFUSED in enforce mode`() {
        val date = LocalDate.of(2019, 6, 11)
        seedManualDay(date)
        interestReports(date, ParticipationOutcome.REFUSED, 0, "posting refused")
        given().contentType("application/json")
            .post("/api/v1/ledger/accounting-days/$date/transitions/TIED_OUT").then().statusCode(409)
        assertThat(statusOf(date)).isEqualTo(AccountingDayStatus.CUTOFF)
    }

    @Test
    @TestSecurity(user = "manual-close-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "manual-close-operator-sub"),
            Claim(key = "preferred_username", value = "manual-close-operator"),
        ],
    )
    fun `manual HTTP tie-out succeeds after a reasoned operator waiver is recorded`() {
        val date = LocalDate.of(2019, 6, 12)
        seedManualDay(date)
        given().contentType("application/json").body("""{"reason":"accepted outage, INC-4"}""")
            .put("/api/v1/ledger/accounting-days/$date/participants/interest/waiver").then().statusCode(200)
        val waiver = onEventLoop { participation.completeness(date) }.reported.getValue("interest")
        assertThat(waiver.outcome).isEqualTo(ParticipationOutcome.WAIVED)
        assertThat(waiver.reason).isEqualTo("accepted outage, INC-4")
        assertThat(waiver.reportedBy).isEqualTo("manual-close-operator-sub")
        given().contentType("application/json")
            .post("/api/v1/ledger/accounting-days/$date/transitions/TIED_OUT").then().statusCode(200)
        assertThat(statusOf(date)).isEqualTo(AccountingDayStatus.TIED_OUT)
    }

    @Test
    @TestSecurity(user = "service-account-close-client", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "close-client"),
            Claim(key = "sub", value = "machine-sub"),
            Claim(key = "preferred_username", value = "service-account-close-client"),
            Claim(key = "client_id", value = "close-client"),
        ],
    )
    fun `machine operator cannot record a waiver while OPA is advisory`() {
        val date = LocalDate.of(2019, 6, 13)
        seedManualDay(date)
        given().contentType("application/json").body("""{"reason":"accepted outage, INC-5"}""")
            .put("/api/v1/ledger/accounting-days/$date/participants/interest/waiver").then().statusCode(403)
        assertThat(onEventLoop { participation.completeness(date) }.reported).doesNotContainKey("interest")
        assertThat(statusOf(date)).isEqualTo(AccountingDayStatus.CUTOFF)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-interest", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-interest"),
            Claim(key = "sub", value = "interest-machine-sub"),
            Claim(key = "preferred_username", value = "service-account-openbank-interest"),
            Claim(key = "client_id", value = "openbank-interest"),
        ],
    )
    fun `report rejects oversized reasons without appending and accepts a Unicode boundary`() {
        val date = LocalDate.of(2019, 7, 10)
        seedManualDay(date)
        val path = "/api/v1/ledger/accounting-days/$date/participants/interest"
        val boundary = "\uD83D\uDE00".repeat(1000)
        given().contentType("application/json")
            .body(mapOf("outcome" to "REFUSED", "reason" to boundary + "x"))
            .put(path).then().statusCode(400)
        assertReasonHistory(date, 0, null)
        given().contentType("application/json")
            .body(mapOf("outcome" to "REFUSED", "reason" to boundary))
            .put(path).then().statusCode(200)
        assertReasonHistory(date, 1, boundary)
        given().contentType("application/json")
            .body(mapOf("outcome" to "REFUSED", "reason" to "x".repeat(1001)))
            .put(path).then().statusCode(400)
        assertReasonHistory(date, 1, boundary)
        assertThat(statusOf(date)).isEqualTo(AccountingDayStatus.CUTOFF)
    }

    @Test
    @TestSecurity(user = "reason-boundary-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "reason-boundary-operator-sub"),
            Claim(key = "preferred_username", value = "reason-boundary-operator"),
        ],
    )
    fun `waiver rejects oversized reasons without appending and accepts a Unicode boundary`() {
        val date = LocalDate.of(2019, 7, 11)
        seedManualDay(date)
        val path = "/api/v1/ledger/accounting-days/$date/participants/interest/waiver"
        val boundary = "\uD83D\uDE00".repeat(1000)
        given().contentType("application/json").body(mapOf("reason" to boundary + "x"))
            .put(path).then().statusCode(400)
        assertReasonHistory(date, 0, null)
        assertThat(statusOf(date)).isEqualTo(AccountingDayStatus.CUTOFF)
        given().contentType("application/json").body(mapOf("reason" to boundary))
            .put(path).then().statusCode(200)
        assertReasonHistory(date, 1, boundary)
        given().contentType("application/json").body(mapOf("reason" to "x".repeat(1001)))
            .put(path).then().statusCode(400)
        assertReasonHistory(date, 1, boundary)
    }

    private fun assertReasonHistory(date: LocalDate, expectedCount: Int, expectedReason: String?) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { db ->
            db.prepareStatement(
                "SELECT reason FROM ledger_close_participation " +
                    "WHERE business_date = ? AND participant = 'interest' ORDER BY seq",
            ).use { statement ->
                statement.setObject(1, date)
                statement.executeQuery().use { rows ->
                    val reasons = readReasons(rows)
                    assertThat(reasons).hasSize(expectedCount)
                    if (expectedReason != null) assertThat(reasons).containsExactly(expectedReason)
                }
            }
        }
    }

    private fun readReasons(rows: ResultSet): List<String> = buildList {
        while (rows.next()) add(rows.getString(1))
    }

    private companion object {
        const val INTEREST_PRINCIPAL = "service-account-openbank-interest"
        val CONTROL_DAY: LocalDate = LocalDate.of(2019, 1, 10)
        val LATE_DAY: LocalDate = LocalDate.of(2019, 1, 11)
        val WAIVED_DAY: LocalDate = LocalDate.of(2019, 2, 10)
        val LATE_REPORT_DAY: LocalDate = LocalDate.of(2019, 3, 10)
        val REFUSED_CONTROL_DAY: LocalDate = LocalDate.of(2019, 4, 9)
        val REFUSED_DAY: LocalDate = LocalDate.of(2019, 4, 10)
        val WAIVER_RESEND_DAY: LocalDate = LocalDate.of(2019, 5, 10)
        const val SEED_ACTOR = "it-seed"
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
