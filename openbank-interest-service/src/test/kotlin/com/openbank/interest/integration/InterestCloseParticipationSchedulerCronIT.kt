// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.interest.application.port.out.CloseJobRunPort
import com.openbank.interest.infrastructure.scheduler.InterestCloseJobs
import com.openbank.libs.close.CloseJobRun
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
 * Drives the REAL `@Scheduled` dispatch of the interest close-participation scheduler (CLAUDE.md:
 * a test that calls a `@Scheduled` method directly supplies the Vert.x context the scheduler does
 * not, so it cannot see a `runBlocking`/context defect).
 *
 * `%test` switches the scheduler off, so the profile switches it back on and shrinks the report
 * cron to every second; the only delivery target is a recording sink enabled by the profile, so no
 * ledger is involved. Only literals in the profile: it loads in a different classloader from the
 * test class, and a computed value would not be the value the application sees.
 */
@QuarkusTest
@TestProfile(InterestCloseParticipationSchedulerCronIT.EverySecondProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
class InterestCloseParticipationSchedulerCronIT {

    class EverySecondProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.interest.close.report-cron" to "* * * * * ?",
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
    fun `the cron itself reports a completed day to the sink`() {
        val yesterday = AccountingClock.bank(clock).today().minusDays(1)
        VertxContextSupport.subscribeAndAwait<Unit> {
            closeJobRuns.record(
                CloseJobRun(yesterday, InterestCloseJobs.ACCRUAL, 3, 0, 0, null, clock.instant()),
            )
        }

        val deadline = System.nanoTime() + WAIT_NANOS
        fun seen() = RecordingSink.delivered.map { it.first to it.second.businessDate }
        while (System.nanoTime() < deadline && (InterestCloseJobs.PARTICIPANT to yesterday) !in seen()) {
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(seen()).contains(InterestCloseJobs.PARTICIPANT to yesterday)
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
