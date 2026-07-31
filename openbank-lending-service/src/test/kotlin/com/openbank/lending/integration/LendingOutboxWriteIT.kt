// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
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
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingOutboxWriteIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            // The scheduled dispatcher would otherwise race this test's assertion, marking the row
            // SENT (or FAILED, with no real broker) before it can be observed as freshly written.
            props["openbank.outbox.dispatch-enabled"] = "false"
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
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
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

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT event_type, payload FROM lending_outbox WHERE aggregate_id = ?",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(loanId))
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).describedAs("a lending_outbox row for loan $loanId").isTrue()
                    assertThat(rs.getString("event_type")).isEqualTo("loan.disbursed")
                    assertThat(rs.getString("payload")).contains(loanId)
                }
            }
        }
    }
}
