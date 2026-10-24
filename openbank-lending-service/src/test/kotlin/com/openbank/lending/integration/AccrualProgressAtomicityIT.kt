// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.application.port.out.AccrualIntent
import com.openbank.lending.application.port.out.AccrualPostingReceipt
import com.openbank.lending.application.port.out.AccrualProgressPort
import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.PreparedAccrualPosting
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP supplies the Vert.x context; JDBC observes committed state and transaction identity. */
@QuarkusTest
@TestSecurity(user = "accrual-proof", roles = ["ROLE_API"])
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class AccrualProgressAtomicityIT {
    @Inject lateinit var db: DataSource

    private val day = LocalDate.of(2098, 10, 2)

    @Test
    fun `receipt completion commits status event and both day counters exactly once`() {
        val f = fixture()
        try {
            val intent = begin(f, day, day.plusDays(1))
            val receipt = receipt(intent)
            complete(intent, receipt, 204)
            val transactions =
                rows(
                    "SELECT xmin::text FROM installment WHERE id=? UNION ALL SELECT xmin::text " +
                        "FROM lending_accrual_progress WHERE installment_id=? UNION ALL SELECT " +
                        "xmin::text FROM lending_outbox WHERE aggregate_id=? AND " +
                        "event_type='loan.interest_accrued' UNION ALL SELECT xmin::text FROM " +
                        "lending_close_job_run WHERE job='accrual' AND business_date IN (?, ?)",
                    f.installment,
                    f.installment,
                    f.loan,
                    day,
                    day.plusDays(1),
                )
            assertThat(transactions).hasSize(5)
            assertThat(transactions.toSet()).hasSize(1)
            assertThat(count("SELECT count(*) FROM lending_outbox WHERE aggregate_id=?", f.loan)).isEqualTo(1)
            assertThat(
                count(
                    "SELECT items_processed FROM lending_close_job_run WHERE business_date=? AND " +
                        "job='accrual'",
                    day,
                ),
            ).isEqualTo(1)
            assertThat(
                count(
                    "SELECT journals_posted FROM lending_close_job_run WHERE business_date=? AND job='accrual'",
                    day.plusDays(1),
                ),
            ).isEqualTo(1)
            assertThat(
                count("SELECT journals_posted FROM lending_close_job_run WHERE business_date=? AND job='accrual'", day),
            ).isZero()
            assertThat(
                count(
                    "SELECT items_processed FROM lending_close_job_run WHERE business_date=? AND job='accrual'",
                    day.plusDays(1),
                ),
            ).isZero()
            assertEventPayload(f)
            complete(intent, receipt, 204)
            assertThat(count("SELECT count(*) FROM lending_outbox WHERE aggregate_id=?", f.loan)).isEqualTo(1)
            assertThat(
                count(
                    "SELECT items_processed FROM lending_close_job_run WHERE business_date=? AND " +
                        "job='accrual'",
                    day,
                ),
            ).isEqualTo(1)
        } finally {
            cleanup(f)
        }
    }

    @Test
    fun `remote acknowledgement followed by local event failure remains pending and exact retry heals`() {
        val prefix = fixture()
        val f = fixture()
        try {
            val first = begin(prefix, day, day)
            val firstReceipt = receipt(first)
            complete(first, firstReceipt, 204)
            val prefixTransaction = rows("SELECT xmin::text FROM installment WHERE id=?", prefix.installment).single()
            val intent = begin(f, day, day)
            val receipt = receipt(intent)
            assertThat(receipt.journalId).isNotEqualTo(firstReceipt.journalId)
            sql(
                "ALTER TABLE lending_outbox ADD CONSTRAINT accrual_test_failure CHECK (aggregate_id <> '${f.loan}'::uuid)",
            )
            try {
                complete(intent, receipt, 409)
                assertThat(
                    count("SELECT count(*) FROM installment WHERE id=? AND interest_accrued", f.installment),
                ).isZero()
                assertThat(
                    count(
                        "SELECT count(*) FROM lending_accrual_progress WHERE installment_id=? AND " +
                            "completed_at IS NOT NULL",
                        f.installment,
                    ),
                ).isZero()
                assertThat(count("SELECT count(*) FROM lending_outbox WHERE aggregate_id=?", f.loan)).isZero()
                assertThat(count("SELECT journals_posted FROM lending_close_job_run WHERE business_date=?", day))
                    .isEqualTo(1)
                assertThat(rows("SELECT xmin::text FROM installment WHERE id=?", prefix.installment).single())
                    .isEqualTo(prefixTransaction)
            } finally {
                sql("ALTER TABLE lending_outbox DROP CONSTRAINT accrual_test_failure")
            }
            // A later clock / caller proposal cannot replace the first committed request or date.
            val replay = begin(f, day.plusDays(1), day.plusDays(1))
            assertThat(replay).isEqualTo(intent)
            complete(replay, receipt, 204)
            assertThat(
                count(
                    "SELECT journals_posted FROM lending_close_job_run WHERE business_date=? AND " +
                        "job='accrual'",
                    day,
                ),
            ).isEqualTo(2)
            assertThat(count("SELECT items_processed FROM lending_close_job_run WHERE business_date=?", day))
                .isEqualTo(2)
            assertThat(count("SELECT count(*) FROM lending_outbox WHERE aggregate_id IN (?,?)", prefix.loan, f.loan))
                .isEqualTo(2)
        } finally {
            cleanup(f)
            cleanup(prefix)
        }
    }

    @Test
    fun `erasing unresolved intent refuses only affected days and cannot be cleared by successful run`() {
        val f = fixture()
        try {
            begin(f, day, day.plusDays(1))
            given().contentType(
                "application/json",
            ).body(DayRequest(day)).post("/test/accrual-progress/start").then().statusCode(204)
            sql("DELETE FROM installment WHERE id=?", f.installment)
            for (d in listOf(day, day.plusDays(1))) {
                assertThat(
                    given().contentType("application/json").body(DayRequest(d))
                        .post("/test/accrual-progress/record-and-read").then().statusCode(200)
                        .extract().jsonPath().getInt("[0].refused"),
                ).isEqualTo(1)
                given().contentType(
                    "application/json",
                ).body(DayRequest(d)).post("/test/accrual-progress/record").then().statusCode(204)
                assertThat(
                    count(
                        "SELECT count(*) FROM lending_close_job_run WHERE business_date=? AND " +
                            "job='accrual' AND unreconciled AND refused>0",
                        d,
                    ),
                ).isEqualTo(1)
            }
            assertThat(
                count("SELECT count(*) FROM lending_close_job_run WHERE business_date=?", day.plusDays(2)),
            ).isZero()
        } finally {
            cleanup(f)
        }
    }

    @Test
    fun `zero interest accepts no receipt and records one item without event or journal`() {
        val f = fixture()
        try {
            sql("UPDATE installment SET interest=0 WHERE id=?", f.installment)
            val intent = begin(f, day, day)
            assertThat(intent.posting).isNull()
            complete(intent, null, 204)
            complete(intent, null, 204)
            assertThat(
                count(
                    "SELECT items_processed FROM lending_close_job_run WHERE business_date=? AND " +
                        "job='accrual'",
                    day,
                ),
            ).isEqualTo(1)
            assertThat(
                count(
                    "SELECT journals_posted FROM lending_close_job_run WHERE business_date=? AND " +
                        "job='accrual'",
                    day,
                ),
            ).isZero()
            assertThat(count("SELECT count(*) FROM lending_outbox WHERE aggregate_id=?", f.loan)).isZero()
        } finally {
            cleanup(f)
        }
    }

    @Test
    fun `one journal cannot confirm two different installment intents`() {
        val first = fixture()
        val second = fixture()
        try {
            val a = begin(first, day, day)
            val b = begin(second, day, day)
            val receipt = receipt(a)
            complete(a, receipt, 204)
            complete(b, receipt.copy(transactionId = b.posting!!.transactionId), 409)
            assertThat(
                count("SELECT count(*) FROM installment WHERE id=? AND interest_accrued", second.installment),
            ).isZero()
            assertThat(count("SELECT count(*) FROM lending_outbox WHERE aggregate_id=?", second.loan)).isZero()
            assertThat(
                count(
                    "SELECT items_processed FROM lending_close_job_run WHERE business_date=? AND " +
                        "job='accrual'",
                    day,
                ),
            ).isEqualTo(1)
        } finally {
            cleanup(first)
            cleanup(second)
        }
    }

    @Test
    fun `missing and mismatched receipt cannot mark installment or create evidence`() {
        val f = fixture()
        try {
            val intent = begin(f, day, day)
            assertThat(
                given().contentType("application/json").body(DayRequest(day))
                    .post("/test/accrual-progress/record-and-read").then().statusCode(200)
                    .extract().jsonPath().getInt("[0].refused"),
            ).isEqualTo(1)
            complete(intent, null, 400)
            complete(intent, receipt(intent).copy(accountingDate = day.plusDays(1)), 400)
            complete(intent, receipt(intent).copy(transactionId = UUID.randomUUID()), 400)
            assertThat(
                count("SELECT count(*) FROM installment WHERE id=? AND interest_accrued", f.installment),
            ).isZero()
            assertThat(count("SELECT count(*) FROM lending_outbox WHERE aggregate_id=?", f.loan)).isZero()
        } finally {
            cleanup(f)
        }
    }

    private fun assertEventPayload(f: Fixture) {
        val payload = rows("SELECT payload FROM lending_outbox WHERE aggregate_id=?", f.loan).single()
        assertThat(payload).contains(
            "\"interest\":\"1.00 EUR\"",
            "\"sourceService\":\"lending\"",
            "\"dueDate\":\"$day\"",
        )
    }

    private fun begin(f: Fixture, work: LocalDate, booked: LocalDate): AccrualIntent =
        given().contentType("application/json").body(BeginRequest(f.loan, f.installment, work, booked))
            .post("/test/accrual-progress/begin").then().statusCode(200).extract().`as`(AccrualIntent::class.java)

    private fun complete(intent: AccrualIntent, receipt: AccrualPostingReceipt?, status: Int) {
        given().contentType("application/json").body(CompleteRequest(intent, receipt))
            .post("/test/accrual-progress/complete").then().statusCode(status)
    }
    private fun receipt(i: AccrualIntent) =
        AccrualPostingReceipt(UUID.randomUUID(), i.posting!!.transactionId, i.posting.accountingDate)

    private data class Fixture(val application: UUID, val loan: UUID, val installment: UUID)
    private fun fixture(): Fixture {
        val f = Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        sql(
            "INSERT INTO loan_application(id, party_id, requested_amount, currency, " +
                "nominal_annual_rate, term_periods, first_due_date, status, proposed_by) " +
                "VALUES (?, ?, 100, 'EUR', 0.05, 12, ?, 'DISBURSED', 'accrual-test')",
            f.application,
            UUID.randomUUID(),
            day,
        )
        sql(
            "INSERT INTO loan(id, application_id, party_id, principal, currency, " +
                "nominal_annual_rate, term_periods, method, first_due_date, status) VALUES " +
                "(?, ?, ?, 100, 'EUR', 0.05, 12, 'ANNUITY', ?, 'ACTIVE')",
            f.loan,
            f.application,
            UUID.randomUUID(),
            day,
        )
        sql(
            "INSERT INTO installment(id, loan_id, number, due_date, currency, " +
                "opening_balance, principal, interest, payment, closing_balance) VALUES (?, " +
                "?, 1, ?, 'EUR', 100, 10, 1, 11, 90)",
            f.installment,
            f.loan,
            day,
        )
        return f
    }
    private fun cleanup(f: Fixture) {
        sql("DELETE FROM installment WHERE loan_id=?", f.loan)
        sql("DELETE FROM lending_outbox WHERE aggregate_id=?", f.loan)
        sql("DELETE FROM loan WHERE id=?", f.loan)
        sql("DELETE FROM loan_application WHERE id=?", f.application)
        sql("DELETE FROM lending_close_job_run WHERE business_date IN (?,?) AND job='accrual'", day, day.plusDays(1))
    }
    private fun sql(statement: String, vararg args: Any) = db.connection.use { c ->
        c.prepareStatement(statement).use { p ->
            args.forEachIndexed { i, a ->
                p.setObject(
                    i + 1,
                    a,
                )
            }
            p.executeUpdate()
            Unit
        }
    }
    private fun rows(statement: String, vararg args: Any): List<String> = db.connection.use { c ->
        c.prepareStatement(statement).use { p ->
            args.forEachIndexed { i, a ->
                p.setObject(
                    i + 1,
                    a,
                )
            }
            p.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }
    }
    private fun count(statement: String, vararg args: Any) = rows(statement, *args).single().toInt()
}

data class BeginRequest(val loan: UUID, val installment: UUID, val work: LocalDate, val booked: LocalDate)
data class CompleteRequest(val intent: AccrualIntent, val receipt: AccrualPostingReceipt?)
data class DayRequest(val day: LocalDate)

/** Test-only transport for exercising the real repository transaction, not a production API. */
@Path("/test/accrual-progress")
@Consumes("application/json")
@Produces("application/json")
class AccrualProgressProofResource(
    private val installments: InstallmentRepository,
    private val progress: AccrualProgressPort,
    private val runs: CloseJobRunPort,
) {
    @POST
    @Path("/begin")
    fun begin(request: BeginRequest): Uni<AccrualIntent> =
        installments.findByLoan(LoanId(request.loan)).flatMap { sources ->
            val source = sources.single { it.id == request.installment }
            progress.begin(source, request.work) {
                val ref = "loan:${source.loanId.value}:inst:${source.number}:accrual"
                if (!source.interest.isPositive()) {
                    null
                } else {
                    PreparedAccrualPosting(
                        ref,
                        UUID.nameUUIDFromBytes(ref.toByteArray(Charsets.UTF_8)),
                        request.booked,
                        "immutable-request",
                    )
                }
            }
        }

    @POST
    @Path("/complete")
    fun complete(request: CompleteRequest): Uni<Void> = progress.complete(
        request.intent,
        request.receipt,
        OffsetDateTime.parse("2098-10-03T10:00:00Z"),
    ).replaceWithVoid()

    @POST
    @Path("/record")
    fun record(request: DayRequest): Uni<Void> = runs.record(
        CloseJobRun(request.day, "accrual", 0, 0, 0, null, Instant.parse("2098-10-03T10:00:00Z")),
    ).replaceWithVoid()

    @POST
    @Path("/start")
    fun start(request: DayRequest): Uni<Void> =
        runs.markStarted(request.day, "accrual", Instant.parse("2098-10-02T01:00:00Z")).replaceWithVoid()

    @POST
    @Path("/record-and-read")
    fun read(request: DayRequest): Uni<List<CloseJobRun>> = runs.record(
        CloseJobRun(request.day, "accrual", 0, 0, 0, null, Instant.parse("2098-10-03T10:00:00Z")),
    ).flatMap { runs.findBetween(request.day, request.day) }
}
