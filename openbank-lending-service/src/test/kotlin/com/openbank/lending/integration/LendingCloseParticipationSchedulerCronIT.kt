// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.integration

import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.lending.infrastructure.servicing.LendingCloseJobs
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
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
 * Drives the REAL `@Scheduled` dispatch of lending's close-participation reporter (CLAUDE.md: a
 * test that calls a `@Scheduled` method directly supplies the Vert.x context the scheduler does
 * not, so it cannot see a `runBlocking`/context defect), and reads its evidence through the real
 * Postgres `lending_close_job_run` table (V28).
 *
 * `%test` switches the reporter off, so the profile switches it back on at every second; the only
 * delivery target is a recording sink enabled by the profile, so no ledger is involved. Literals
 * only: the profile loads in a different classloader from the test class.
 */
@QuarkusTest
@TestProfile(LendingCloseParticipationSchedulerCronIT.EverySecondProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class LendingCloseParticipationSchedulerCronIT {

    class EverySecondProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "lending.close.report-cron" to "* * * * * ?",
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.http.test-port" to "0",
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
    fun `the cron reports a completed day, and a refused accrual as REFUSED`() {
        val bank = AccountingClock.bank(clock)
        val yesterday = bank.today().minusDays(1)
        val dayBefore = bank.today().minusDays(2)
        VertxContextSupport.subscribeAndAwait<Unit> {
            closeJobRuns.record(CloseJobRun(yesterday, LendingCloseJobs.ACCRUAL, 3, 2, 0, null, clock.instant()))
                .flatMap {
                    closeJobRuns.record(
                        CloseJobRun(yesterday, LendingCloseJobs.PROVISIONING, 7, 0, 0, null, clock.instant()),
                    )
                }
                .flatMap {
                    closeJobRuns.record(
                        CloseJobRun(
                            dayBefore,
                            LendingCloseJobs.ACCRUAL,
                            0,
                            0,
                            1,
                            LendingCloseJobs.REASON_DAY_CLOSED,
                            clock.instant(),
                        ),
                    )
                }
                .flatMap {
                    closeJobRuns.record(
                        CloseJobRun(dayBefore, LendingCloseJobs.PROVISIONING, 7, 0, 0, null, clock.instant()),
                    )
                }
        }

        val deadline = System.nanoTime() + WAIT_NANOS
        fun seen(day: java.time.LocalDate) = RecordingSink.delivered
            .filter { it.first == LendingCloseJobs.PARTICIPANT && it.second.businessDate == day }
            .map { it.second }
        while (System.nanoTime() < deadline && (seen(yesterday).isEmpty() || seen(dayBefore).isEmpty())) {
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(seen(yesterday)).anySatisfy {
            assertThat(it.outcome).isEqualTo(CloseOutcome.RAN)
            assertThat(it.journalsPosted).isEqualTo(2)
            assertThat(it.itemsProcessed).isEqualTo(10)
        }
        assertThat(seen(dayBefore)).anySatisfy {
            assertThat(it.outcome).isEqualTo(CloseOutcome.REFUSED)
            assertThat(it.reason).contains("ACCOUNTING_DAY_CLOSED")
        }
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
