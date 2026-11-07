// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.billing.integration

import com.openbank.billing.application.port.out.CloseJobRunPort
import com.openbank.billing.infrastructure.scheduler.BillingCloseJobs
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the REAL `@Scheduled` dispatch of billing's close-participation reporter (CLAUDE.md: a
 * direct call supplies the Vert.x context the scheduler does not), reading the evidence through the
 * real Postgres `billing_close_job_run` (V10) and `assessed_fee` tables.
 *
 * `%test` switches the scheduler off; the profile switches it on with the reporter at every second
 * and a 40-day lookback, so the last 1st of the month (the cycle day) is always inside it. The cycle
 * itself stays a no-op (no accounts configured, discovery off) and its 03:00-on-the-1st cron does not
 * fire during the test. Delivery goes to a recording sink. Literals only in the profile.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(BillingCloseParticipationSchedulerCronIT.EverySecondProfile::class)
class BillingCloseParticipationSchedulerCronIT {

    class EverySecondProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.billing.scheduler.enabled" to "true",
            "openbank.billing.scheduler.cron" to "0 0 3 1 * ?",
            "openbank.billing.scheduler.discovery-enabled" to "false",
            "openbank.billing.close.report-cron" to "* * * * * ?",
            "openbank.billing.close.lookback-days" to "40",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(RecordingSink::class.java)
    }

    @Alternative
    @ApplicationScoped
    class RecordingSink : CloseParticipationSink {
        override fun deliver(participant: String, report: CloseParticipationReport): Uni<Unit> {
            delivered += participant to report
            return Uni.createFrom().item(Unit)
        }

        companion object {
            val delivered = CopyOnWriteArrayList<Pair<String, CloseParticipationReport>>()
        }
    }

    @Inject
    lateinit var closeJobRuns: CloseJobRunPort

    @Inject
    lateinit var clock: Clock

    @Test
    fun `the cron reports the cycle day RAN and the other days SKIPPED`() {
        val today = AccountingClock.bank(clock).today()
        // The most recent 1st strictly before today (the reporter only covers completed days).
        val cycleDay = today.minusDays(1).withDayOfMonth(1)
        val otherDay = if (cycleDay.plusDays(1) < today) cycleDay.plusDays(1) else cycleDay.minusDays(1)
        VertxContextSupport.subscribeAndAwait<Unit> {
            closeJobRuns.record(CloseJobRun(cycleDay, BillingCloseJobs.CYCLE, 0, 0, 0, null, clock.instant()))
        }

        fun seen(day: java.time.LocalDate) = RecordingSink.delivered
            .filter { it.first == BillingCloseJobs.PARTICIPANT && it.second.businessDate == day }
            .map { it.second }
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline && (seen(cycleDay).isEmpty() || seen(otherDay).isEmpty())) {
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(seen(cycleDay)).anySatisfy { assertThat(it.outcome).isEqualTo(CloseOutcome.RAN) }
        assertThat(seen(otherDay)).anySatisfy { assertThat(it.outcome).isEqualTo(CloseOutcome.SKIPPED) }
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
