// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * The four-eyes state is left only through `/decision` by a second person, over real HTTP and a real
 * database. The generic `/advance` must answer 409 FOUR_EYES_DECISION_REQUIRED and leave the row in
 * FOUR_EYES — checked with a plain JDBC read, so a refusal that still wrote the row would be caught.
 * Ordered methods because `@TestSecurity` is fixed per method and each step needs its own principal.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class OriginationFourEyesDecisionIT {

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var applicationId: String

    private companion object {
        const val PROPOSER = "four-eyes-it-proposer"
        const val CHECKER = "four-eyes-it-checker"
        const val DISBURSER = "four-eyes-it-disburser"
        const val ADVANCES_TO_DISBURSABLE = 3
        const val ADVANCES_TO_FOUR_EYES = 4
    }

    private fun storedStatus(): String = dataSource.connection.use { c ->
        c.prepareStatement("select status::text from loan_application where id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(applicationId))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "application $applicationId not stored" }
                rs.getString(1)
            }
        }
    }

    /** Durable refusal evidence for this application, by code — read straight from the outbox. */
    private fun refusals(code: String, action: String): Int = dataSource.connection.use { c ->
        c.prepareStatement(
            "select count(*) from lending_outbox where event_type = 'credit.application.transition.refused' " +
                "and aggregate_id = ? and payload like ? and payload like ?",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(applicationId))
            ps.setString(2, "%\"code\":\"$code\"%")
            ps.setString(3, "%\"attemptedAction\":\"$action\"%")
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    @Test
    @Order(1)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `1 - the proposer applies and drives the application to the four-eyes gate`() {
        val body = """
            {"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
        """.trimIndent()
        applicationId = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }
        repeat(ADVANCES_TO_FOUR_EYES) {
            Given {
                contentType("application/json")
            } When {
                post("/api/v1/lending/applications/$applicationId/advance")
            } Then {
                statusCode(200)
            }
        }
        assertThat(storedStatus()).isEqualTo("FOUR_EYES")
    }

    @Test
    @Order(2)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `2 - advance on FOUR_EYES is refused with 409 and the state is unchanged`() {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$applicationId/advance")
        } Then {
            statusCode(409)
            body("error", equalTo("FOUR_EYES_DECISION_REQUIRED"))
            body("message", containsString("/decision"))
            body("proposedBy", nullValue())
        }
        assertThat(storedStatus()).isEqualTo("FOUR_EYES")
        assertThat(refusals("FOUR_EYES_DECISION_REQUIRED", "ADVANCE")).isEqualTo(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_CREDIT_RISK", "ROLE_ADMIN"])
    fun `3 - the proposer cannot decide their own application`() {
        Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/decision")
        } Then {
            statusCode(409)
            body("error", equalTo("SEGREGATION_OF_DUTIES"))
        }
        assertThat(storedStatus()).isEqualTo("FOUR_EYES")
        assertThat(refusals("SEGREGATION_OF_DUTIES", "DECIDE")).isEqualTo(1)
    }

    @Test
    @Order(4)
    @TestSecurity(user = CHECKER, roles = ["ROLE_CREDIT_RISK"])
    fun `4 - a second person's approval moves it to OFFERED and records the decider`() {
        Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When {
            post("/api/v1/lending/applications/$applicationId/decision")
        } Then {
            statusCode(200)
            body("status", equalTo("OFFERED"))
            body("decidedBy", equalTo(CHECKER))
        }
        assertThat(storedStatus()).isEqualTo("OFFERED")
    }

    @Test
    @Order(5)
    @TestSecurity(user = CHECKER, roles = ["ROLE_LENDING_OFFICER"])
    fun `5 - the offer advances normally to READY_TO_DISBURSE once decided`() {
        repeat(ADVANCES_TO_DISBURSABLE) {
            Given {
                contentType("application/json")
            } When {
                post("/api/v1/lending/applications/$applicationId/advance")
            } Then {
                statusCode(200)
            }
        }
        assertThat(storedStatus()).isEqualTo("READY_TO_DISBURSE")
    }

    @Test
    @Order(6)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `6 - the proposer cannot disburse their own loan`() {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$applicationId/disburse")
        } Then {
            statusCode(409)
            body("error", equalTo("SEGREGATION_OF_DUTIES"))
        }
        assertThat(storedStatus()).isEqualTo("READY_TO_DISBURSE")
        assertThat(refusals("SEGREGATION_OF_DUTIES", "DISBURSE")).isEqualTo(1)
    }

    @Test
    @Order(7)
    @TestSecurity(user = DISBURSER, roles = ["ROLE_LENDING_OFFICER"])
    fun `7 - a ready application with no recorded decision is not disbursable by anyone`() {
        // The shape an application could reach before the decision state was enforced: ready, undecided.
        dataSource.connection.use { c ->
            c.prepareStatement("update loan_application set decided_by = null where id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(applicationId))
                check(ps.executeUpdate() == 1)
            }
        }
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$applicationId/disburse")
        } Then {
            statusCode(409)
            body("error", equalTo("FOUR_EYES_DECISION_MISSING"))
        }
        assertThat(storedStatus()).isEqualTo("READY_TO_DISBURSE")
        assertThat(refusals("FOUR_EYES_DECISION_MISSING", "DISBURSE")).isEqualTo(1)
    }
}
