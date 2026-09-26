// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.it.PostgresRedisTestResource
import com.openbank.lending.it.TestBorrowerCreditPort
import com.openbank.lending.it.TestRecordingLedgerPostingPort
import io.quarkus.arc.ClientProxy
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * The #10746 ledger backfill, end-to-end over HTTP against a real Postgres, with the ledger port
 * replaced by a double that emulates the ledger's idempotency contract (one journal per reference).
 *
 * Synthetic fixtures only: two loans disbursed in 2020 (far before anything else the suite seeds, so
 * the `disbursedBefore` scope isolates them), one CZK and one EUR, one with accrued-then-paid rows and
 * one with a row paid before accrual, plus a two-period provisioning history.
 *
 * What it proves, in order: the dry-run writes nothing; four-eyes (execute before approval and
 * self-approval are refused); a failed leg stops that loan and leaves the request re-runnable; the
 * re-run completes WITHOUT a duplicate journal; the borrower-credit port is never called; and the
 * post-backfill Loans Receivable per currency equals lending's unpaid principal.
 *
 * Runs with the servicing schedulers OFF ([NoServicingSchedulersProfile]). The seeded 2020 dates make
 * every unaccrued installment due, so an interest-accrual pass (first tick 30 s after boot) or a
 * provisioning cycle landing mid-class posts to these loans, moves the book, changes the plan hash,
 * and turns the later steps into 409s. That was a timing flake, not a backfill defect.
 */
@QuarkusTest
@TestProfile(LedgerBackfillIT.NoServicingSchedulersProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class LedgerBackfillIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var ledgerPort: LedgerPostingPort

    @Inject
    lateinit var borrowerCreditPort: BorrowerCreditPort

    private val ledger get() = ClientProxy.unwrap(ledgerPort) as TestRecordingLedgerPostingPort
    private val borrower get() = ClientProxy.unwrap(borrowerCreditPort) as TestBorrowerCreditPort

    private val czkLoan = UUID.randomUUID()
    private val eurLoan = UUID.randomUUID()
    private val cutover = LocalDate.now(ZoneId.of("Europe/Prague"))
    private lateinit var requestId: String
    private var expectedLegs = 0
    private var applicationId: UUID? = null

    private fun mine() = ledger.journals.filterKeys {
        it.contains(czkLoan.toString()) || it.contains(eurLoan.toString())
    }

    @Test
    @Order(1)
    @TestSecurity(user = "backfill-seeder", roles = ["ROLE_ADMIN"])
    fun `1 - seed two synthetic loans with history that never reached the ledger`() {
        val applicationId = UUID.fromString(
            Given {
                contentType("application/json")
                body(
                    """{"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"1000.00","currency":{"code":"CZK"}},
                    "nominalAnnualRate":0.05,"termPeriods":3,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}""",
                )
            } When {
                post("/api/v1/lending/applications")
            } Then {
                statusCode(201)
            } Extract {
                jsonPath().getString("id")
            },
        )
        this.applicationId = applicationId
        sql(
            """
            INSERT INTO loan(id, application_id, party_id, principal, currency, nominal_annual_rate, term_periods,
                method, first_due_date, status, disbursed_at)
            VALUES ('$czkLoan', '$applicationId', '${UUID.randomUUID()}', 300.00, 'CZK', 0.12, 3, 'ANNUITY',
                    '2020-02-15', 'ACTIVE', '2020-01-15T09:00:00Z'),
                   ('$eurLoan', '$applicationId', '${UUID.randomUUID()}', 200.00, 'EUR', 0.06, 2, 'ANNUITY',
                    '2020-02-15', 'ACTIVE', '2020-01-15T09:00:00Z')
            """,
        )
        sql(
            """
            INSERT INTO installment(id, loan_id, number, due_date, currency, opening_balance, principal, interest,
                payment, closing_balance, paid, paid_at, interest_accrued, accrued_at)
            VALUES (gen_random_uuid(), '$czkLoan', 1, '2020-02-15', 'CZK', 300, 100, 3, 103, 200,
                    true, '2020-02-15T10:00:00Z', true, '2020-02-15T08:00:00Z'),
                   (gen_random_uuid(), '$czkLoan', 2, '2020-03-15', 'CZK', 200, 100, 2, 102, 100,
                    true, '2020-03-15T10:00:00Z', true, '2020-03-15T08:00:00Z'),
                   (gen_random_uuid(), '$czkLoan', 3, '2020-04-15', 'CZK', 100, 100, 1, 101, 0,
                    false, null, false, null),
                   (gen_random_uuid(), '$eurLoan', 1, '2020-02-15', 'EUR', 200, 100, 1, 101, 100,
                    true, '2020-02-10T10:00:00Z', false, null),
                   (gen_random_uuid(), '$eurLoan', 2, '2020-03-15', 'EUR', 100, 100, 0.5, 100.5, 0,
                    false, null, false, null)
            """,
        )
        sql(
            """
            INSERT INTO loan_provisioning(id, loan_id, period, as_of, outstanding_balance, currency, days_past_due,
                bucket, stage, expected_credit_loss, created_at, model_version)
            VALUES (gen_random_uuid(), '$czkLoan', '2020-01', '2020-01-31', 300, 'CZK', 0, 'CURRENT', 'STAGE_1',
                    6.00, now(), 'it'),
                   (gen_random_uuid(), '$czkLoan', '2020-02', '2020-02-29', 200, 'CZK', 0, 'CURRENT', 'STAGE_1',
                    4.50, now(), 'it'),
                   (gen_random_uuid(), '$eurLoan', '2020-01', '2020-01-31', 200, 'EUR', 0, 'CURRENT', 'STAGE_1',
                    2.00, now(), 'it')
            """,
        )
        // CZK: disb + 2 accrual + 2 principal + 2 settlement + 2 provisioning = 9; EUR: disb + principal + interest + prov = 4
        expectedLegs = 13
    }

    @Test
    @Order(2)
    @TestSecurity(user = "backfill-maker", roles = ["ROLE_FINANCE"])
    fun `2 - the dry-run returns the journal set and ties out, and writes nothing`() {
        val before = ledger.recorded.size
        val body =
            Given {
                queryParam("cutoverDate", cutover.toString())
                queryParam("disbursedBefore", "2020-02-01")
            } When {
                get("/api/v1/lending/ledger-backfill/plan")
            } Then {
                statusCode(200)
            } Extract { jsonPath() }
        assertThat(body.getBoolean("executable")).isTrue()
        assertThat(body.getInt("journalCount")).isEqualTo(expectedLegs)
        val tie = body.getList<Map<String, Any>>("plan.tieOut").associateBy { it["currency"] }
        assertThat(BigDecimal(tie.getValue("CZK")["loansReceivableAfter"].toString())).isEqualByComparingTo("100")
        assertThat(BigDecimal(tie.getValue("EUR")["loansReceivableAfter"].toString())).isEqualByComparingTo("100")
        val gl = body.getList<Map<String, Any>>("glTotals").associateBy { "${it["code"]}/${it["currency"]}" }
        assertThat(BigDecimal(gl.getValue("1200/CZK")["net"].toString())).isEqualByComparingTo("100")
        assertThat(BigDecimal(gl.getValue("1400/CZK")["net"].toString())).isEqualByComparingTo("-4.50")
        assertThat(ledger.recorded.size).describedAs("a dry-run posts nothing").isEqualTo(before)
        assertThat(count("SELECT count(*) FROM ledger_backfill_request")).isZero()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "backfill-maker", roles = ["ROLE_FINANCE"])
    fun `3 - maker proposes, and cannot execute or approve their own request`() {
        requestId = Given {
            contentType("application/json")
            body("""{"cutoverDate":"$cutover","disbursedBefore":"2020-02-01"}""")
        } When {
            post("/api/v1/lending/ledger-backfill/requests")
        } Then {
            statusCode(201)
        } Extract { jsonPath().getString("id") }

        Given { queryParam("execute", true) } When {
            post("/api/v1/lending/ledger-backfill/requests/$requestId/execute")
        } Then { statusCode(422) }

        Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When {
            post("/api/v1/lending/ledger-backfill/requests/$requestId/decide")
        } Then { statusCode(422) }

        assertThat(mine()).describedAs("nothing posts before a second person approves").isEmpty()
    }

    @Test
    @Order(4)
    @TestSecurity(user = "backfill-checker", roles = ["ROLE_ADMIN"])
    fun `4 - a different admin approves`() {
        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"IT"}""")
        } When {
            post("/api/v1/lending/ledger-backfill/requests/$requestId/decide")
        } Then {
            statusCode(200)
        }
        assertThat(state()).isEqualTo("APPROVED")

        // #10904: an approved plan gets no second request that could later run as a no-op "success".
        Given {
            contentType("application/json")
            body("""{"cutoverDate":"$cutover","disbursedBefore":"2020-02-01"}""")
        } When {
            post("/api/v1/lending/ledger-backfill/requests")
        } Then {
            statusCode(409)
            body("error", containsString(requestId))
        }
        assertThat(count("SELECT count(*) FROM ledger_backfill_request")).isEqualTo(1)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "backfill-maker", roles = ["ROLE_FINANCE"])
    fun `5 - without execute=true the approved request only returns its plan`() {
        Given { queryParam("execute", false) } When {
            post("/api/v1/lending/ledger-backfill/requests/$requestId/execute")
        } Then {
            statusCode(200)
        }
        assertThat(mine()).isEmpty()
        assertThat(state()).isEqualTo("APPROVED")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "backfill-maker", roles = ["ROLE_FINANCE"])
    fun `6 - a failed leg stops that loan only and leaves the request re-runnable`() {
        ledger.failOnce += "loan:$czkLoan:inst:1:principal"
        val body = Given { queryParam("execute", true) } When {
            post("/api/v1/lending/ledger-backfill/requests/$requestId/execute")
        } Then {
            statusCode(200)
        } Extract { jsonPath() }
        assertThat(body.getBoolean("execution.complete")).isFalse()
        val loans = body.getList<Map<String, Any>>("execution.loans").associateBy { it["loanId"] }
        assertThat(loans.getValue(czkLoan.toString())["status"]).isEqualTo("FAILED")
        assertThat(loans.getValue(eurLoan.toString())["status"]).isEqualTo("POSTED")
        // Legs after the failure were not attempted — nothing depending on the missing leg was booked.
        assertThat(mine().keys).doesNotContain("loan:$czkLoan:inst:1:interest", "loan:$czkLoan:provisioning:2020-01")
        assertThat(state()).isEqualTo("APPROVED")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "backfill-maker", roles = ["ROLE_FINANCE"])
    fun `7 - the re-run completes with exactly one journal per leg, value-dated, and ties out`() {
        val attemptsBefore = ledger.recorded.count {
            it.reference.contains(czkLoan.toString()) ||
                it.reference.contains(eurLoan.toString())
        }
        val body = Given { queryParam("execute", true) } When {
            post("/api/v1/lending/ledger-backfill/requests/$requestId/execute")
        } Then {
            statusCode(200)
        } Extract { jsonPath() }
        assertThat(body.getBoolean("execution.complete")).isTrue()
        assertThat(state()).isEqualTo("EXECUTED")

        assertReplaysAreNotCountedAsPosted(body)

        val journals = mine()
        // The re-run re-sent every leg (replays included) and the ledger kept ONE journal per reference.
        val attempts = ledger.recorded.count {
            it.reference.contains(czkLoan.toString()) ||
                it.reference.contains(eurLoan.toString())
        }
        assertThat(attempts - attemptsBefore).isEqualTo(expectedLegs)
        assertThat(journals).hasSize(expectedLegs)
        assertThat(journals.keys).contains(
            "loan:$czkLoan:disbursement",
            "loan:$czkLoan:inst:1:accrual",
            "loan:$czkLoan:inst:1:principal",
            "loan:$czkLoan:inst:1:interest",
            "loan:$czkLoan:provisioning:2020-02",
            "loan:$eurLoan:inst:1:interest",
        )
        assertThat(journals.getValue("loan:$czkLoan:inst:1:interest").kind).isEqualTo(PostingKind.INTEREST_SETTLEMENT)
        assertThat(journals.getValue("loan:$eurLoan:inst:1:interest").kind).isEqualTo(PostingKind.INTEREST)
        assertThat(journals.getValue("loan:$czkLoan:provisioning:2020-02").amount.amount).isEqualByComparingTo("-1.50")
        journals.values.forEach { assertThat(it.accountingDate).isEqualTo(cutover) }
        assertThat(journals.getValue("loan:$czkLoan:disbursement").valueDate).isEqualTo(LocalDate.parse("2020-01-15"))

        // GL only: the borrower-credit port was never asked to move money for these loans.
        assertThat(
            borrower.calls.filter {
                it.contains(czkLoan.toString()) || it.contains(eurLoan.toString())
            },
        ).isEmpty()

        // Tie-out: Loans Receivable per currency after the backfill == lending's unpaid principal.
        mapOf("CZK" to czkLoan, "EUR" to eurLoan).forEach { (ccy, loan) ->
            val receivable = journals.values.filter { it.amount.currency.code == ccy }.sumOf {
                when (it.kind) {
                    PostingKind.DISBURSEMENT -> it.amount.amount
                    PostingKind.PRINCIPAL_REPAYMENT -> it.amount.amount.negate()
                    else -> BigDecimal.ZERO
                }
            }
            val unpaid =
                decimal("SELECT coalesce(sum(principal), 0) FROM installment WHERE loan_id = '$loan' AND NOT paid")
            assertThat(
                receivable,
            ).describedAs("1200-series/$ccy vs lending unpaid principal").isEqualByComparingTo(unpaid)
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "backfill-maker", roles = ["ROLE_FINANCE"])
    fun `8 - an executed request cannot run again`() {
        Given { queryParam("execute", true) } When {
            post("/api/v1/lending/ledger-backfill/requests/$requestId/execute")
        } Then { statusCode(422) }
    }

    // #10618: the maker above is ROLE_FINANCE and the checker ROLE_ADMIN; roles outside the pair get 403.
    @Test
    @Order(9)
    @TestSecurity(user = "desk-operator", roles = ["ROLE_OPERATOR", "ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER"])
    fun `9 - a role outside finance and admin is refused the dry-run`() {
        Given {
            queryParam("cutoverDate", cutover.toString())
            queryParam("disbursedBefore", "2020-02-01")
        } When {
            get("/api/v1/lending/ledger-backfill/plan")
        } Then { statusCode(403) }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "backfill-auditor", roles = ["ROLE_FINANCE"])
    fun `10 - the request history shows who proposed, approved and executed it`() {
        Given { queryParam("limit", 5) } When {
            get("/api/v1/lending/ledger-backfill/requests")
        } Then {
            statusCode(200)
            body("requests[0].id", equalTo(requestId))
            body("requests[0].state", equalTo("EXECUTED"))
            body("requests[0].proposedBy", equalTo("backfill-maker"))
            body("requests[0].decidedBy", equalTo("backfill-checker"))
            body("requests[0].proposedAt", notNullValue())
        }
        Given { this } When { get("/api/v1/lending/ledger-backfill/requests/$requestId") } Then {
            statusCode(200)
            body("executedBy", equalTo("backfill-maker"))
        }
        Given { this } When { get("/api/v1/lending/ledger-backfill/requests/${UUID.randomUUID()}") } Then
            { statusCode(404) }
        Given { queryParam("limit", 0) } When { get("/api/v1/lending/ledger-backfill/requests") } Then {
            statusCode(400)
        }
    }

    /** The IT database is shared by every @QuarkusTest: leave nothing behind for book-wide counts (LendingSummaryIT). */
    @AfterAll
    fun cleanup() {
        val loans = "'$czkLoan', '$eurLoan'"
        sql("DELETE FROM lending_outbox WHERE aggregate_id IN (SELECT id FROM ledger_backfill_request)")
        sql("DELETE FROM ledger_backfill_request")
        sql("DELETE FROM installment WHERE loan_id IN ($loans)")
        sql("DELETE FROM loan_provisioning WHERE loan_id IN ($loans)")
        sql("DELETE FROM loan WHERE id IN ($loans)")
        applicationId?.let { sql("DELETE FROM loan_application WHERE id = '$it'") }
    }

    /**
     * #10904: the EUR loan was fully booked by the first run, so every leg is a replay now and the loan
     * must NOT count as posted. The CZK loan had legs left to book, so it does.
     */
    private fun assertReplaysAreNotCountedAsPosted(body: io.restassured.path.json.JsonPath) {
        val loans = body.getList<Map<String, Any>>("execution.loans").associateBy { it["loanId"] }
        assertThat(loans.getValue(eurLoan.toString())["status"]).isEqualTo("ALREADY_POSTED")
        assertThat(loans.getValue(eurLoan.toString())["legsPosted"]).isEqualTo(0)
        assertThat(loans.getValue(czkLoan.toString())["status"]).isEqualTo("POSTED")
        val result = dataSource.connection.use { c ->
            c.prepareStatement("SELECT last_result FROM ledger_backfill_request WHERE id = ?::uuid").use { st ->
                st.setString(1, requestId)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }
        val alreadyPosted = Regex("\"loansAlreadyPosted\":(\\d+)").find(result)?.groupValues?.get(1)?.toInt()
        // >= 1, not == 1: the IT database is shared, so other suites' loans may also be in scope.
        assertThat(alreadyPosted).isNotNull().isGreaterThanOrEqualTo(1)
    }

    private fun state(): String = dataSource.connection.use { c ->
        c.prepareStatement("SELECT state FROM ledger_backfill_request WHERE id = ?::uuid").use { st ->
            st.setString(1, requestId)
            st.executeQuery().use { rs ->
                check(rs.next())
                rs.getString(1)
            }
        }
    }

    private fun sql(statement: String) = dataSource.connection.use { c ->
        c.createStatement().use { it.executeUpdate(statement.trimIndent()) }
    }

    private fun count(query: String): Int = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(query).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun decimal(query: String): BigDecimal = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(query).use { rs ->
                rs.next()
                rs.getBigDecimal(1)
            }
        }
    }

    /**
     * `off` is Quarkus' literal for "never schedule this method". Literals only: a profile is loaded in
     * a different classloader from the test class, so a computed value could diverge between the two.
     */
    class NoServicingSchedulersProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "lending.servicing.accrual.every" to "off",
            "lending.provisioning.cycle.every" to "off",
        )
    }
}
