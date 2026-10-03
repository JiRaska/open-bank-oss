// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

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
import java.time.LocalDate
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
                "SELECT event_type, payload, created_at FROM lending_outbox WHERE aggregate_id = ?",
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
