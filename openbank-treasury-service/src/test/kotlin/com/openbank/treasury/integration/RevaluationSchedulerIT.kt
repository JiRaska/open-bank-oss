// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate

/**
 * ADR-0344 D7/D8 through the REAL `@Scheduled` dispatch: a direct call would supply the Vert.x
 * context the scheduler lacks, so only the cron proves the reactive reads work from the scheduler
 * thread (HR000068, check-no-runblocking-in-scheduled). The unposted-revaluation gauge reads NaN
 * until a run has measured the store; a run that reached the database sets it to 0 (no deal here),
 * and with no fair-value deal the run records the accounting day's REVALUATION step as SKIPPED.
 */
@QuarkusTest
@TestProfile(RevaluationSchedulerIT.EverySecond::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RevaluationSchedulerIT {

    /** Literal values only: a profile loads in a different classloader from the test. */
    class EverySecond : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.treasury.revaluation.enabled" to "true",
            "openbank.treasury.revaluation.cron" to "* * * * * ?",
            "openbank.treasury.fixing.enabled" to "false",
            "openbank.treasury.accrual.enabled" to "false",
        )
    }

    @Inject
    lateinit var meterRegistry: MeterRegistry

    private fun unposted(): Double? = meterRegistry.find("openbank.treasury.revaluation.unposted").gauge()?.value()

    @Test
    fun `the cron run measures the unposted revaluations from the scheduler thread`() {
        val deadline = System.nanoTime() + WAIT_NANOS
        var value = unposted()
        while ((value == null || value.isNaN()) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            value = unposted()
        }
        assertThat(value).describedAs("unposted gauge set by the scheduled run").isEqualTo(0.0)
    }

    @Test
    fun `the cron records the REVALUATION step of the accounting day`() {
        val today = AccountingClock.bank(Clock.systemUTC()).today()
        val deadline = System.nanoTime() + WAIT_NANOS
        var status = step(today)
        while (status == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            status = step(today)
        }
        assertThat(status).describedAs("REVALUATION step written by the scheduled run").isEqualTo("SKIPPED")
    }

    private fun step(day: LocalDate): String? {
        val c = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            c.getValue("quarkus.datasource.jdbc.url", String::class.java),
            c.getValue("quarkus.datasource.username", String::class.java),
            c.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(
                "select status from treasury_day_completion where business_date = ? and step = 'REVALUATION' " +
                    "order by recorded_at desc limit 1",
            ).use { ps ->
                ps.setObject(1, day)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
