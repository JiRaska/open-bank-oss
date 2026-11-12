// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.application.port.out.CreditAssessment
import com.openbank.lending.application.port.out.CreditBureauPort
import com.openbank.libs.domain.money.Money
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.arc.Unremovable
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Customer-requested human review of an engine decline, over real HTTP and a real database.
 * Ordered methods because `@TestSecurity` is fixed per method and each step needs its own principal.
 *
 * The declined application must stay DECLINED (read back with plain JDBC), a repeat request is the same
 * review and writes no second row or event, a stranger reads 404, and a decline a PERSON made is refused
 * 409 NOT_ENGINE_DECLINE.
 */
@QuarkusTest
@TestProfile(DeclineReviewIT.IntakeProfile::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class DeclineReviewIT {

    class IntakeProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "lending.intake.enabled" to "true",
            "lending.intake.caller-principal" to "service-account-openbank-customer-edge",
            "lending.intake.nominal-annual-rate" to "0.05",
        )

        // The shipped bureau binding reports "unavailable", which makes the engine fail closed to
        // REFER for every application, so no engine DECLINE can occur with it. This profile alone
        // swaps in a bureau that answers, so the affordability floor can decide.
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(AnsweringBureau::class.java)
    }

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var firstApp: String
    private lateinit var secondApp: String
    private lateinit var humanDeclinedApp: String
    private lateinit var firstReview: String

    private companion object {
        const val EDGE = "service-account-openbank-customer-edge"
        const val PARTY_A = "a0a0a0a0-0000-0000-0000-00000000000a"
        const val PARTY_B = "b0b0b0b0-0000-0000-0000-00000000000b"
        const val PARTY_C = "c0c0c0c0-0000-0000-0000-00000000000c"
        const val MAX_ADVANCES = 8
        const val REASON = "income evidence reviewed by a person"
    }

    private fun scalar(sql: String, id: String): String? = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "no row for $id" }
                rs.getString(1)
            }
        }
    }

    private fun status(id: String): String = scalar("select status::text from loan_application where id = ?", id)!!

    private fun outboxCount(type: String, aggregateId: String): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from lending_outbox where event_type = ? and aggregate_id = ?").use { ps ->
            ps.setString(1, type)
            ps.setObject(2, UUID.fromString(aggregateId))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun submit(party: String, income: String): String = Given {
        contentType("application/json")
        body(
            """
            {"partyId":"$party","requestedAmount":{"amount":"120000.00","currency":{"code":"CZK"}},
             "nominalAnnualRate":0.08,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}",
             "verifiedIncomeMonthly":{"amount":"$income","currency":{"code":"CZK"}},
             "existingDebtServiceMonthly":{"amount":"0","currency":{"code":"CZK"}},
             "existingDebtOutstanding":{"amount":"0","currency":{"code":"CZK"}},
             "ageYears":35,"residency":"CZ","employmentTenureMonths":48}
            """.trimIndent(),
        )
    } When {
        post("/api/v1/lending/applications")
    } Then {
        statusCode(201)
    } Extract {
        path<String>("id")
    }

    /** Advance until the engine has decided: DECLINED, or on to the four-eyes gate. */
    private fun advance(id: String) {
        repeat(MAX_ADVANCES) {
            if (status(id) in setOf("DECLINED", "FOUR_EYES")) return
            Given { contentType("application/json") } When {
                post("/api/v1/lending/applications/$id/advance")
            } Then { statusCode(200) }
        }
    }

    private fun customerRequest(party: String, applicationId: String) = Given {
        header("X-Customer-Party-Id", party)
    } When {
        post("/api/v1/lending/intake/applications/$applicationId/decline-review")
    }

    private fun decideApplication(id: String, approve: Boolean) = Given {
        contentType("application/json")
        body("""{"approve":$approve,"reason":"$REASON"}""")
    } When {
        post("/api/v1/lending/applications/$id/decision")
    }

    @Test
    @Order(1)
    @TestSecurity(user = "dr-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `1 - the engine declines two unaffordable applications and a person declines a third`() {
        firstApp = submit(PARTY_A, income = "2000.00")
        secondApp = submit(PARTY_B, income = "2000.00")
        humanDeclinedApp = submit(PARTY_C, income = "60000.00")
        listOf(firstApp, secondApp, humanDeclinedApp).forEach(::advance)

        listOf(firstApp, secondApp).forEach {
            assertThat(status(it)).isEqualTo("DECLINED")
            assertThat(scalar("select decision_outcome from loan_application where id = ?", it)).isEqualTo("DECLINE")
            assertThat(scalar("select decided_by from loan_application where id = ?", it)).isNull()
        }
        assertThat(status(humanDeclinedApp)).isEqualTo("FOUR_EYES")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "dr-it-decliner", roles = ["ROLE_CREDIT_RISK"])
    fun `2 - a person declines the referred application`() {
        decideApplication(humanDeclinedApp, approve = false) Then {
            statusCode(200)
            body("status", equalTo("DECLINED"))
        }
    }

    @Test
    @Order(3)
    @TestSecurity(
        user = EDGE,
        roles = ["ROLE_OPERATOR"],
        augmentors = [IntakeJwtIdentityAugmentor::class],
        attributes = [SecurityAttribute(key = "money-wire-intake-fixture", value = "synthetic-jwt-only")],
    )
    fun `3 - the customer asks - a repeat is the same review - a stranger and a human decline are refused`() {
        firstReview = customerRequest(PARTY_A, firstApp) Then {
            statusCode(201)
            body("status", equalTo("REQUESTED"))
            body("applicationId", equalTo(firstApp))
        } Extract { path<String>("id") }

        customerRequest(PARTY_A, firstApp) Then {
            statusCode(200)
            body("id", equalTo(firstReview))
        }
        assertThat(outboxCount("credit.decline_review.requested", firstApp)).isEqualTo(1)

        customerRequest(PARTY_B, firstApp) Then { statusCode(404) }

        // The engine-only guard: a decline a person made is never looped back.
        customerRequest(PARTY_C, humanDeclinedApp) Then {
            statusCode(409)
            body("error", equalTo("NOT_ENGINE_DECLINE"))
        }

        customerRequest(PARTY_B, secondApp) Then { statusCode(201) }
        assertThat(status(firstApp)).isEqualTo("DECLINED")
    }

    @Test
    @Order(4)
    @TestSecurity(
        user = EDGE,
        roles = ["ROLE_OPERATOR"],
        augmentors = [IntakeJwtIdentityAugmentor::class],
        attributes = [SecurityAttribute(key = "money-wire-intake-fixture", value = "synthetic-jwt-only")],
    )
    fun `4 - a repeat request still answers with the same review`() {
        customerRequest(PARTY_A, firstApp) Then {
            statusCode(200)
            body("id", equalTo(firstReview))
            body("status", equalTo("REQUESTED"))
        }
        assertThat(outboxCount("credit.decline_review.requested", firstApp)).isEqualTo(1)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "some-operator", roles = ["ROLE_OPERATOR"])
    fun `5 - an operator is not the customer edge`() {
        customerRequest(PARTY_A, firstApp) Then { statusCode(403) }
    }
}

/** Test-only bureau that completes its check with no adverse data; enabled by [DeclineReviewIT.IntakeProfile] only. */
@Alternative
@Unremovable
@ApplicationScoped
class AnsweringBureau : CreditBureauPort {
    override fun assess(partyId: java.util.UUID, requestedAmount: Money): Uni<CreditAssessment> =
        Uni.createFrom().item(CreditAssessment(score = 700, hasAdverseData = false, source = "it", available = true))
}
