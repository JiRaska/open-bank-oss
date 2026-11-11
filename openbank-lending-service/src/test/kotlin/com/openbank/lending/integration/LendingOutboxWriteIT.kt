// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Reproduces the gap fixed by [com.openbank.lending.infrastructure.adapter.JpaLoanEventEmitter]:
 * before that adapter existed, the only bound [com.openbank.lending.application.port.out.LoanEventEmitter]
 * was the `@Default` no-op (`LoggingLoanEventEmitter`), so every `events.emit(...)` call in
 * `LendingService` silently no-op'd and no domain event ever reached `lending_outbox`.
 * `LendingServiceTest` mocks `LoanEventEmitter` entirely, so it could not (and still does not) catch
 * this — this boots the real app against a Testcontainers Postgres, drives the origination →
 * disbursement flow through the real REST endpoints (matching `LendingResourceAuthzTest`'s
 * `@TestSecurity` pattern — a direct CDI call into a `@WithTransaction` repository from the bare test
 * thread fails with "No current Vertx context found"; only a real HTTP request carries one), and
 * asserts an actual row lands in the table via a plain JDBC read (sidestepping the same Vert.x-context
 * requirement for the assertion itself). Same "released-but-never-booted" defect class
 * `LendingBootSmokeIT` guards against.
 *
 * The three steps run as ordered, dependent `@Test` methods (apply / decide / disburse each need a
 * distinct acting principal — four-eyes and segregation-of-duties, ADR-0028 D5 — and `@TestSecurity`
 * is fixed per test method) sharing state via `PER_CLASS` instance fields.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class LendingOutboxWriteIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            // The scheduled dispatcher would otherwise race this test's assertion, marking the row
            // SENT (or FAILED, with no real broker) before it can be observed as freshly written.
            props["openbank.outbox.dispatch-enabled"] = "false"
            // ADR-0314 D5: this flow books a FLOATING loan end to end, so the V18 columns, their CHECK
            // constraint and the entity mapping are exercised against real Postgres.
            props["lending.origination.floating-rate-enabled"] = "true"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var applicationId: String
    private lateinit var loanId: String
    private val json = ObjectMapper()

    private companion object {
        const val ADVANCES_TO_FOUR_EYES = 4
        const val ADVANCES_TO_DISBURSABLE = 3
    }

    @Test
    @Order(1)
    @TestSecurity(user = "outbox-it-proposer", roles = ["ROLE_LENDING_OFFICER"])
    fun `1 - apply for a loan`() {
        val body = """
            {"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}",
            "rateTerms":{"rateType":"FLOATING","rateIndex":"PRIBOR_3M","spread":0.025,
              "resetFrequencyMonths":3,"nextResetDate":"${LocalDate.now().plusMonths(4)}"}}
        """.trimIndent()

        val response = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(201)
        } Extract {
            this
        }
        applicationId = response.jsonPath().getString("id")
        assertThat(applicationId).isNotBlank()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "outbox-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `2 - advance the application to the four-eyes gate`() {
        repeat(ADVANCES_TO_FOUR_EYES) {
            Given {
                contentType("application/json")
            } When {
                post("/api/v1/lending/applications/$applicationId/advance")
            } Then {
                statusCode(200)
            }
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "outbox-it-checker", roles = ["ROLE_CREDIT_RISK"])
    fun `3 - approve the application`() {
        Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/decision")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "outbox-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `4 - advance the approved offer to READY_TO_DISBURSE`() {
        repeat(ADVANCES_TO_DISBURSABLE) {
            Given {
                contentType("application/json")
            } When {
                post("/api/v1/lending/applications/$applicationId/advance")
            } Then {
                statusCode(200)
            }
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "outbox-it-disburser", roles = ["ROLE_LENDING_OFFICER"])
    fun `5 - disburse and assert the loan_disbursed row lands in lending_outbox`() {
        val response = Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$applicationId/disburse")
        } Then {
            statusCode(201)
        } Extract {
            this
        }
        loanId = response.jsonPath().getString("id")
        assertThat(loanId).isNotBlank()
        // #11107: the loan the REST call returns already carries the number the repository drew.
        val contractNumber = response.jsonPath().getString("contractNumber")
        assertThat(contractNumber).matches("UV-\\d{4}-\\d{6,}")

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT event_type, payload, created_at FROM lending_outbox " +
                    "WHERE aggregate_id = ? AND event_type = 'loan.disbursed'",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(loanId))
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).describedAs("a lending_outbox row for loan $loanId").isTrue()
                    assertThat(rs.getString("event_type")).isEqualTo("loan.disbursed")
                    assertThat(rs.getString("payload")).contains(loanId)
                    assertThat(rs.getString("payload")).contains("\"rateType\":\"FLOATING\"")
                    assertThat(rs.getString("payload")).contains("\"contractNumber\":\"$contractNumber\"")
                    // #9003 falsification: do not accept "the default is Instant.now()" as proof —
                    // assert the row that lands through the REAL emitter is not epoch-stamped
                    // (44 of 45 sandbox rows carried 1970-01-01, the #3272 defect class).
                    assertThat(rs.getTimestamp("created_at").toInstant())
                        .isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"))
                }
            }
            assertBookingIsOneTransaction(conn)
            assertDisbursementFact(conn)
            // The loan row carries the terms the application was approved with.
            conn.prepareStatement(
                "SELECT rate_type, rate_index, spread, reset_frequency_months, next_reset_date, contract_number " +
                    "FROM loan WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(loanId))
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("rate_type")).isEqualTo("FLOATING")
                    assertThat(rs.getString("contract_number")).isEqualTo(contractNumber)
                    assertThat(rs.getString("rate_index")).isEqualTo("PRIBOR_3M")
                    assertThat(rs.getBigDecimal("spread")).isEqualByComparingTo("0.025")
                    assertThat(rs.getInt("reset_frequency_months")).isEqualTo(3)
                    assertThat(rs.getDate("next_reset_date").toLocalDate()).isEqualTo(LocalDate.now().plusMonths(4))
                }
            }
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "outbox-it-repayment", roles = ["ROLE_LENDING_OFFICER"])
    fun `repayment commits the paid installment and its exposure fact together`() {
        val installmentId = firstInstallment()
        Given { contentType("application/json") } When {
            post("/api/v1/lending/loans/$loanId/installments/$installmentId/repay")
        } Then {
            statusCode(200)
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT i.paid, i.paid_at, i.principal, i.interest, i.xmin::text, f.xmin::text, f.payload,
                       (SELECT sum(other.principal) FROM installment other
                        WHERE other.loan_id = i.loan_id AND NOT other.paid)
                FROM installment i JOIN lending_outbox f ON f.aggregate_id = i.loan_id
                WHERE i.id = ? AND f.event_type = 'loan.exposure_fact' AND f.payload::jsonb->>'kind' = 'REPAID'
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, installmentId)
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getBoolean(1)).isTrue()
                    val paidAt = rs.getTimestamp(2).toInstant()
                    val fact = json.readTree(rs.getString(7))
                    assertThat(Instant.parse(fact["effectiveAt"].asText()))
                        .isBetween(paidAt.minusNanos(1_000), paidAt.plusNanos(1_000))
                    assertThat(fact["loanStatus"].asText()).isEqualTo("ACTIVE")
                    assertThat(fact["installmentId"].asText()).isEqualTo(installmentId.toString())
                    assertThat(fact["principalRepaid"].decimalValue()).isEqualByComparingTo(rs.getBigDecimal(3))
                    assertThat(fact["interestRepaid"].decimalValue()).isEqualByComparingTo(rs.getBigDecimal(4))
                    assertThat(fact["unpaidScheduledPrincipal"].decimalValue())
                        .isEqualByComparingTo(rs.getBigDecimal(8))
                    assertThat(rs.getString(6)).isEqualTo(rs.getString(5))
                    assertThat(rs.next()).isFalse()
                }
            }
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "outbox-it-risk", roles = ["ROLE_CREDIT_RISK"])
    fun `write-off commits its terminal exposure fact with the loan status`() {
        val startedAt = Instant.now()
        Given {
            contentType("application/json")
            body("""{"writtenOffBy":"outbox-it-risk","reason":"synthetic integration fixture"}""")
        } When {
            post("/api/v1/lending/loans/$loanId/writeoff")
        } Then {
            statusCode(200)
        }
        val finishedAt = Instant.now()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT l.status, l.xmin::text, f.xmin::text, f.payload
                FROM loan l JOIN lending_outbox f ON f.aggregate_id = l.id
                WHERE l.id = ? AND f.event_type = 'loan.exposure_fact' AND f.payload::jsonb->>'kind' = 'ENDED'
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.fromString(loanId))
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("WRITTEN_OFF")
                    assertThat(rs.getString(3)).isEqualTo(rs.getString(2))
                    val fact = json.readTree(rs.getString(4))
                    assertThat(fact["loanStatus"].asText()).isEqualTo("WRITTEN_OFF")
                    assertThat(Instant.parse(fact["effectiveAt"].asText())).isBetween(startedAt, finishedAt)
                    assertThat(rs.next()).isFalse()
                }
            }
        }
    }

    private fun firstInstallment(): UUID = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT id FROM installment WHERE loan_id = ? ORDER BY number LIMIT 1").use { ps ->
            ps.setObject(1, UUID.fromString(loanId))
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getObject(1, UUID::class.java)
            }
        }
    }

    private fun assertDisbursementFact(conn: java.sql.Connection) {
        conn.prepareStatement(
            """
            SELECT f.payload, f.xmin::text, d.xmin::text, l.disbursed_at, l.principal, l.status
            FROM loan l JOIN lending_outbox f ON f.aggregate_id = l.id
            JOIN lending_outbox d ON d.aggregate_id = l.id AND d.event_type = 'loan.disbursed'
            WHERE l.id = ? AND f.event_type = 'loan.exposure_fact' AND f.payload::jsonb->>'kind' = 'DISBURSED'
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(loanId))
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                val fact = json.readTree(rs.getString(1))
                val disbursedAt = rs.getObject(4, OffsetDateTime::class.java).toInstant()
                assertThat(Instant.parse(fact["effectiveAt"].asText()))
                    .isBetween(disbursedAt.minusNanos(1_000), disbursedAt.plusNanos(1_000))
                assertThat(Instant.parse(fact["cashConfirmedAt"].asText())).isAfterOrEqualTo(disbursedAt)
                assertThat(fact["originalPrincipal"].decimalValue()).isEqualByComparingTo(rs.getBigDecimal(5))
                assertThat(fact["loanStatus"].asText()).isEqualTo(rs.getString(6))
                assertThat(rs.getString(2)).isEqualTo(rs.getString(3))
                assertThat(rs.next()).isFalse()
            }
        }
    }

    private fun assertBookingIsOneTransaction(conn: java.sql.Connection) {
        // #11626: the loan, its schedule, the DISBURSED claim and the transition evidence are
        // one transaction. Postgres stamps each row version with xmin, the id of the transaction
        // that wrote it, so all of them must carry the loan row's value. `loan.disbursed` is
        // deliberately NOT in this set: it is written after the remote ledger posting and the
        // borrower credit, so it carries a later transaction by design.
        conn.prepareStatement(
            """
            SELECT l.xmin::text,
                   (SELECT a.xmin::text FROM loan_application a WHERE a.id = l.application_id),
                   (SELECT string_agg(DISTINCT i.xmin::text, ',') FROM installment i WHERE i.loan_id = l.id),
                   (SELECT string_agg(DISTINCT o.xmin::text, ',') FROM lending_outbox o
                      WHERE o.aggregate_id = l.application_id AND o.event_type = 'credit.application.transition'
                        AND o.payload LIKE '%disbursement booked%'),
                   (SELECT count(*) FROM installment i WHERE i.loan_id = l.id)
            FROM loan l WHERE l.id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.fromString(loanId))
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                val loanXmin = rs.getString(1)
                assertThat(rs.getInt(5)).describedAs("schedule rows").isEqualTo(12)
                assertThat(rs.getString(2))
                    .describedAs("the DISBURSED claim on loan_application shares the loan row's transaction")
                    .isEqualTo(loanXmin)
                assertThat(rs.getString(3))
                    .describedAs("every installment row shares the loan row's transaction")
                    .isEqualTo(loanXmin)
                assertThat(rs.getString(4))
                    .describedAs("the 'disbursement booked' evidence row shares the loan row's transaction")
                    .isEqualTo(loanXmin)
            }
        }
    }
}
