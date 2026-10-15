// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.contract

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import javax.sql.DataSource

/** Real HTTP/database proof that old and current FINREP fixture evidence does not leak between replays. */
@QuarkusTest
@QuarkusTestResource(value = com.openbank.ledger.it.PostgresTestResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
class FinrepFrozenMonthPactSeedCompatibilityIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `older then current contract evidence remains isolated`() {
        verify(false)
        verify(true)
    }

    @Test
    fun `current then older contract evidence remains isolated`() {
        verify(true)
        verify(false)
    }

    private fun verify(current: Boolean) {
        val date = LocalDate.parse(if (current) "2000-06-30" else "2026-06-30")
        FinrepFrozenMonthPactSeed.seed(dataSource, date, current, resetFixture = true)
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("delete from ledger_closed_period_trial_balance_line")
                }
            }
        }.hasMessageContaining("immutable")
        val periods = given().queryParam("from", "1970-01-01").queryParam("to", "9999-12-31")
            .get("/api/v1/ledger/periods").then().statusCode(200).extract().jsonPath().getList<String>("to")
        val expected = if (current) {
            (1..6).map {
                LocalDate.of(2000, it, 1).withDayOfMonth(LocalDate.of(2000, it, 1).lengthOfMonth()).toString()
            }
        } else {
            listOf(date.toString())
        }
        assertThat(periods).containsExactlyInAnyOrderElementsOf(expected)
        val body = given().get("/api/v1/ledger/periods/MONTH/$date/frozen-trial-balance")
            .then().statusCode(200).extract().jsonPath()
        assertThat(body.getString("period")).isEqualTo("MONTH:${date.toString().substring(0, 7)}")
        assertThat(body.getBoolean("balanced")).isTrue()
        if (current) {
            given().get("/api/v1/ledger/periods/MONTH/$date/frozen-year-to-date-trial-balance").then().statusCode(200)
        }
    }
}
