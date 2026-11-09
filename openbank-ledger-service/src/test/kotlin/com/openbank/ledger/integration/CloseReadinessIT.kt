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
            "openbank.ledger.day-lock.mode" to "shadow",
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

    @Inject
    lateinit var journalWatermark: com.openbank.ledger.infrastructure.persistence.repository.JournalReadWatermark

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
    fun `a later shadow posting invalidates readiness without rewriting the original close evidence`() {
        closeMonth(omitFirstBilling = false)
        val day = month.atDay(1)
        val original = storedEvidence(day)
        given().get(READINESS).then().statusCode(200).body("eligibleForEnforcement", equalTo(true))

        postJournal(day)

        given().get(READINESS).then().statusCode(200)
            .body("eligibleForEnforcement", equalTo(false))
            .body("days[0].blockers", hasItem("JOURNALS_CHANGED_AFTER_CLOSE"))
        assertThat(storedEvidence(day)).isEqualTo(original)
    }

    @Test
    fun `archive removal plus a replacement posting cannot hide behind the same journal count`() {
        postJournal(LocalDate.of(1971, 1, 1))
        closeMonth(omitFirstBilling = false)
        val date = month.atDay(1)
        val original = storedEvidence(date)
        val before = onEventLoopPopulation(date)
        given().get(READINESS).then().body("eligibleForEnforcement", equalTo(true))
        sql("ALTER TABLE journal_entries DETACH PARTITION journal_entries_default")
        try {
            postJournal(date)
            val after = onEventLoopPopulation(date)
            assertThat(after.count).isEqualTo(before.count)
            assertThat(after.fingerprint).isNotEqualTo(before.fingerprint)
            given().get(READINESS).then().statusCode(200)
                .body("eligibleForEnforcement", equalTo(false))
                .body("days[0].blockers", hasItem("JOURNALS_CHANGED_AFTER_CLOSE"))
            assertThat(storedEvidence(date)).isEqualTo(original)
        } finally {
            sql("ALTER TABLE journal_entries ATTACH PARTITION journal_entries_default DEFAULT")
        }
    }

    private fun onEventLoopPopulation(date: LocalDate) = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { journalWatermark.populationThrough(date) }
    }

    @Test
    fun `posting into the next month preserves the preceding closed month proof`() {
        closeMonth(omitFirstBilling = false)
        val original = storedEvidence(month.atEndOfMonth())

        postJournal(month.plusMonths(1).atDay(1))

        given().get(READINESS).then().statusCode(200).body("eligibleForEnforcement", equalTo(true))
        assertThat(storedEvidence(month.atEndOfMonth())).isEqualTo(original)
    }

    @Test
    fun `a shadow reversal invalidates historical proof while retaining its captured snapshot`() {
        val day = month.atDay(1)
        val journal = postJournal(day)
        closeMonth(omitFirstBilling = false)
        val original = storedEvidence(day)
        given().get(READINESS).then().statusCode(200).body("eligibleForEnforcement", equalTo(true))

        given().contentType("application/json").body(
            mapOf("reason" to "close-readiness reversal proof", "reversedBy" to UUID.randomUUID()),
        ).post("/api/v1/journals/$journal/reverse").then().statusCode(200)

        given().get(READINESS).then().statusCode(200)
            .body("eligibleForEnforcement", equalTo(false))
            .body("days[0].blockers", hasItem("JOURNALS_CHANGED_AFTER_CLOSE"))
        assertThat(storedEvidence(day)).isEqualTo(original)
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
    fun `new source work after close invalidates proof even without a journal`() {
        closeMonth(omitFirstBilling = false)
        val day = month.atDay(1)
        val original = storedEvidence(day)
        val population = onEventLoopPopulation(day)
        given().get(READINESS).then().body("eligibleForEnforcement", equalTo(true))

        report(day, "billing", ParticipationOutcome.REFUSED, "b".repeat(64), 0)
        given().get(READINESS).then().statusCode(200)
            .body("eligibleForEnforcement", equalTo(false))
            .body("days[0].blockers", hasItem("SOURCE_EVIDENCE_CHANGED_AFTER_CLOSE"))
        // Even a successful zero-amount fee run is new work, not a repair of the frozen close.
        report(day, "billing", ParticipationOutcome.RAN, "c".repeat(64), 0)
        given().get(READINESS).then().body("eligibleForEnforcement", equalTo(false))
            .body("days[0].blockers", hasItem("SOURCE_EVIDENCE_CHANGED_AFTER_CLOSE"))
        assertThat(onEventLoopPopulation(day)).isEqualTo(population)
        assertThat(storedEvidence(day)).isEqualTo(original)
    }

    @Test
    fun `an unchanged source receipt retry preserves the original monthly proof`() {
        closeMonth(omitFirstBilling = false)
        val day = month.atDay(1)
        val original = storedEvidence(day)
        val receipts = readScalar("SELECT count(*) FROM ledger_close_participation WHERE business_date = '$day'")
        report(day, "billing")
        given().get(READINESS).then().statusCode(200).body("eligibleForEnforcement", equalTo(true))
        assertThat(readScalar("SELECT count(*) FROM ledger_close_participation WHERE business_date = '$day'"))
            .isEqualTo(receipts)
        assertThat(storedEvidence(day)).isEqualTo(original)
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
        val outboxBefore = readScalar("SELECT count(*) FROM ledger_outbox")
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
            assertThat(readScalar("SELECT count(*) FROM ledger_outbox")).isEqualTo(outboxBefore)
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
                val at = Instant.now()
                tieOutRuns.save(
                    TieOutRunRecord(
                        UUID.randomUUID(), date, at, TieOutRunStatus.OK, 4, 0, 0,
                        startedAt = at, journalCount = journalWatermark.countThrough(date),
                        journalFingerprint = journalWatermark.populationThrough(date).fingerprint,
                    ),
                )
            }
        }
    }

    private fun transition(date: LocalDate, status: String, expectedStatus: Int) {
        given().contentType("application/json").post("$DAYS/$date/transitions/$status")
            .then().statusCode(expectedStatus)
    }

    private fun report(
        date: LocalDate,
        name: String,
        outcome: ParticipationOutcome = ParticipationOutcome.RAN,
        sourceRunId: String = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$date|$name".toByteArray()).joinToString("") { "%02x".format(it) },
        count: Int = 1,
    ) {
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) {
                participation.report(
                    ReportParticipationCommand(
                        date,
                        name,
                        outcome,
                        count,
                        count,
                        if (outcome == ParticipationOutcome.REFUSED) "Pending monthly fee work" else null,
                        config.participants().getValue(name).principal(),
                        if (outcome ==
                            ParticipationOutcome.RAN
                        ) {
                            readinessConfig.requiredJobs().getValue(name)
                        } else {
                            emptySet()
                        },
                        sourceRunId,
                    ),
                )
            }
        }
    }

    private fun postJournal(date: LocalDate): String {
        val transaction = UUID.randomUUID()
        fun line(code: String, side: String) = mapOf(
            "glAccountId" to readScalar("SELECT id FROM gl_accounts WHERE code = '$code'"),
            "side" to side,
            "amount" to "10.00",
            "currencyCode" to "CZK",
            "baseAmount" to "10.00",
            "baseCurrencyCode" to "CZK",
        )
        return given().contentType("application/json").body(
            mapOf(
                "idempotencyKey" to "close-readiness-$transaction",
                "transactionId" to transaction,
                "entryDate" to date.toString(),
                "valueDate" to date.toString(),
                "createdBy" to UUID.randomUUID(),
                "lines" to listOf(line("1100", "DEBIT"), line("4003", "CREDIT")),
            ),
        ).post("/api/v1/journals").then().statusCode(201).extract().path("id")
    }

    private fun storedEvidence(date: LocalDate): String = readScalar(
        "SELECT CAST(row_to_json(e) AS text) FROM ledger_close_evidence e WHERE business_date = DATE '$date'",
    )

    private fun readScalar(statement: String): String {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use {
                it.executeQuery(statement).use { rows ->
                    assertThat(rows.next()).isTrue()
                    rows.getString(1)
                }
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
