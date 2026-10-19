// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate

/**
 * ADR-0342 through the REAL `@Scheduled` dispatch (a direct call would supply the Vert.x context the
 * scheduler lacks): treasury reports a day whose due steps finished, withholds a day with a FAILED
 * step, and reports REFUSED for a day the ledger closed under it. The rows are the day-completion
 * record (Postgres), read back through `GET /day-completion/{date}`.
 */
@QuarkusTest
@TestProfile(TreasuryCloseParticipationCronIT.EverySecond::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class TreasuryCloseParticipationCronIT {

    /** Literal values only: a profile loads in a different classloader from the test. */
    class EverySecond : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.treasury.close.report-cron" to "* * * * * ?",
            "openbank.treasury.close.lookback-days" to "5",
            "openbank.treasury.accrual.enabled" to "false",
        )
    }

    @Inject
    lateinit var clock: Clock

    private fun insert(day: LocalDate, status: String, count: Int, journals: Int, refused: Int, detail: String?) {
        val c = ConfigProvider.getConfig()
        DriverManager.getConnection(
            c.getValue("quarkus.datasource.jdbc.url", String::class.java),
            c.getValue("quarkus.datasource.username", String::class.java),
            c.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(
                "insert into treasury_day_completion (id, business_date, step, status, item_count, detail, " +
                    "recorded_at, journals_posted, refused_count) values " +
                    "(nextval('treasury_day_completion_seq'), ?, 'ACCRUALS', ?, ?, ?, now(), ?, ?)",
            ).use {
                it.setObject(1, day)
                it.setString(2, status)
                it.setInt(3, count)
                it.setString(4, detail)
                it.setInt(5, journals)
                it.setInt(6, refused)
                it.executeUpdate()
            }
        }
    }

    private fun await(day: LocalDate) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline && RecordingCloseSink.delivered.none { it.second.businessDate == day }) {
            Thread.sleep(POLL_MILLIS)
        }
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `the cron reports RAN and REFUSED days and withholds a FAILED one`() {
        val today = AccountingClock.bank(clock).today()
        val ran = today.minusDays(1)
        val refused = today.minusDays(2)
        val failed = today.minusDays(3)
        insert(ran, "DONE", 4, 4, 0, null)
        insert(refused, "REFUSED", 2, 1, 1, "1 posting(s) refused: the ledger accounting day is closed")
        insert(failed, "FAILED", 1, 1, 0, "1 item(s) failed (0 refused by a closed day); see the service log")

        await(ran)
        await(refused)
        Thread.sleep(PASSES_MS) // several more ticks: the FAILED day is still withheld
        val mine = RecordingCloseSink.delivered.filter { it.first == "treasury" }.map { it.second }
        assertThat(mine.first { it.businessDate == ran }.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(mine.first { it.businessDate == ran }.journalsPosted).isEqualTo(4)
        assertThat(mine.first { it.businessDate == refused }.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(mine.none { it.businessDate == failed }).isTrue()
        // A re-offered identical report is not re-sent.
        assertThat(mine.count { it.businessDate == ran }).isEqualTo(1)

        given().`when`().get("/api/v1/treasury/day-completion/$ran").then().statusCode(200)
            .body("closeParticipation.outcome", equalTo("RAN"))
            .body("steps.find { it.step == 'ACCRUALS' }.journals", equalTo(4))
        given().`when`().get("/api/v1/treasury/day-completion/$failed").then().statusCode(200)
            .body("closeParticipation", nullValue())
            .body("steps.find { it.step == 'ACCRUALS' }.status", equalTo("FAILED"))
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 250L
        const val PASSES_MS = 3_000L
    }
}
