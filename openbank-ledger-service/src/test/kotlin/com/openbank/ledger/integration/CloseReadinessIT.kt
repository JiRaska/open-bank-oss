// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.CloseParticipationUseCase
import com.openbank.ledger.application.port.`in`.ReportParticipationCommand
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.application.usecase.CloseParticipantsConfig
import com.openbank.ledger.application.usecase.CloseReadinessConfig
import com.openbank.ledger.domain.model.ParticipationOutcome
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
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
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Real HTTP, Flyway/Postgres and transaction proof; identities are test OIDC, not live JWT evidence. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(CloseReadinessIT.ShadowProfile::class)
@TestSecurity(user = "close-readiness-operator", roles = ["ROLE_OPERATOR"])
@OidcSecurity(
    claims = [
        Claim(key = "azp", value = "openbank-admin-ui"),
        Claim(key = "sub", value = "close-readiness-operator-sub"),
        Claim(key = "preferred_username", value = "close-readiness-operator"),
    ],
)
class CloseReadinessIT {
    class ShadowProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "openbank.ledger.close.participants-mode" to "shadow",
            "authz.enforce" to "false",
        )
    }

    @Inject
    lateinit var participation: CloseParticipationUseCase

    @Inject
    lateinit var config: CloseParticipantsConfig

    @Inject
    lateinit var accountingClock: AccountingClock

    @Inject
    lateinit var readinessConfig: CloseReadinessConfig

    @Inject
    lateinit var tieOutRuns: TieOutRunRepository

    private val month: YearMonth get() = YearMonth.from(accountingClock.today()).minusMonths(1)

    @BeforeEach
    fun resetCalendarInIsolatedTestDatabase() {
        sql("TRUNCATE ledger_close_evidence, ledger_close_participation, ledger_accounting_day CASCADE")
    }

    @Test
    fun `a whole completed month qualifies and a missing day cannot qualify`() {
        closeMonth(omitFirstBilling = false, omitLastDay = true)
        given().get(READINESS).then().statusCode(200)
            .body("eligibleForEnforcement", equalTo(false))
            .body("observedDays", equalTo(month.lengthOfMonth() - 1))
        closeDay(month.atEndOfMonth(), omitBilling = false)
        given().get(READINESS).then().statusCode(200)
            .body("month", equalTo(month.toString()))
            .body("eligibleForEnforcement", equalTo(true))
            .body("requiredDays", equalTo(month.lengthOfMonth()))
            .body("observedDays", equalTo(month.lengthOfMonth()))
        // One natural-key snapshot per day; a repeated transition is refused, not overwritten.
        transition(month.atEndOfMonth(), "TIED_OUT", 409)
        given().get(READINESS).then().body("eligibleForEnforcement", equalTo(true))
    }

    @Test
    fun `late fee completion cannot turn an incomplete shadow close into a clean historical month`() {
        closeMonth(omitFirstBilling = true)
        val day = month.atDay(1)
        report(day, "billing")
        given().get("$DAYS/$day/participants").then().statusCode(200)
            .body("complete", equalTo(true))
            .body("participantsMode", equalTo("shadow"))
        // Live reports are now complete, but the first-day fee cycle was MISSING at close.
        given().get(READINESS).then().statusCode(200)
            .body("eligibleForEnforcement", equalTo(false))
            .body("days[0].blockers", hasItem("MISSING:billing"))
    }

    @Test
    fun `no evidence and an incomplete month never present as ready`() {
        given().get(READINESS).then().statusCode(200)
            .body("eligibleForEnforcement", equalTo(false))
            .body("observedDays", equalTo(0))
        given().queryParam("month", YearMonth.from(accountingClock.today()).toString())
            .get(READINESS).then().statusCode(400)
        given().queryParam("month", "2024-99").get(READINESS).then().statusCode(400)
    }

    @Test
    fun `failed evidence insertion rolls back both the day transition and its outbox event`() {
        val day = month.atDay(1)
        openAndCutoff(day)
        config.participants().keys.forEach { report(day, it) }
        sql(
            "CREATE FUNCTION reject_test_close_evidence() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN RAISE EXCEPTION ''injected evidence failure''; END'; " +
                "CREATE TRIGGER reject_test_close_evidence BEFORE INSERT ON ledger_close_evidence " +
                "FOR EACH ROW EXECUTE FUNCTION reject_test_close_evidence()",
        )
        try {
            transition(day, "TIED_OUT", 500)
            given().get("$DAYS/$day").then().statusCode(200).body("status", equalTo("CUTOFF"))
            given().get(READINESS).then().body("observedDays", equalTo(0))
        } finally {
            sql(
                "DROP TRIGGER reject_test_close_evidence ON ledger_close_evidence; " +
                    "DROP FUNCTION reject_test_close_evidence()",
            )
        }
        transition(day, "TIED_OUT", 200)
        given().get(READINESS).then().body("observedDays", equalTo(1))
    }

    private fun closeMonth(omitFirstBilling: Boolean, omitLastDay: Boolean = false) {
        val last = month.lengthOfMonth() - if (omitLastDay) 1 else 0
        for (day in 1..last) closeDay(month.atDay(day), omitFirstBilling && day == 1)
    }

    private fun closeDay(date: LocalDate, omitBilling: Boolean) {
        openAndCutoff(date)
        config.participants().keys.filterNot { omitBilling && it == "billing" }.forEach { report(date, it) }
        freshTieOut(date)
        transition(date, "TIED_OUT", 200)
    }

    private fun openAndCutoff(date: LocalDate) {
        given().contentType("application/json").post("$DAYS/$date").then().statusCode(201)
        transition(date, "CUTOFF", 200)
    }

    private fun freshTieOut(date: LocalDate) {
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) {
                tieOutRuns.save(TieOutRunRecord(UUID.randomUUID(), date, Instant.now(), TieOutRunStatus.OK, 4, 0, 0))
            }
        }
    }

    private fun transition(date: LocalDate, status: String, expectedStatus: Int) {
        given().contentType("application/json").post("$DAYS/$date/transitions/$status")
            .then().statusCode(expectedStatus)
    }

    private fun report(date: LocalDate, name: String) {
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) {
                participation.report(
                    ReportParticipationCommand(
                        date,
                        name,
                        ParticipationOutcome.RAN,
                        1,
                        1,
                        null,
                        config.participants().getValue(name).principal(),
                        readinessConfig.requiredJobs().getValue(name),
                    ),
                )
            }
        }
    }

    private fun sql(statement: String) {
        val cfg = ConfigProvider.getConfig()
        DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection -> connection.createStatement().use { assertThat(it.execute(statement)).isFalse() } }
    }

    private companion object {
        const val DAYS = "/api/v1/ledger/accounting-days"
        const val READINESS = "/api/v1/ledger/close-readiness"
    }
}
