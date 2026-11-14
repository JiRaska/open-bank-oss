// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.application.port.out.CreditAssessment
import com.openbank.lending.application.port.out.CreditBureauPort
import com.openbank.lending.domain.model.FirstDueDate
import com.openbank.libs.domain.money.Money
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.arc.Unremovable
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
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
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Customer-requested human review of an engine decline, over real HTTP and a real database.
 * Ordered methods because `@TestSecurity` is fixed per method and each step needs its own principal.
 *
 * One flow per outcome, then the guards. The declined application must stay DECLINED in every case
 * (read back with plain JDBC); an overturn must leave a SUCCESSOR in FOUR_EYES that a different
 * operator can approve through the existing decision endpoint, and the reviewer must not be able to.
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

    private lateinit var confirmedApp: String
    private lateinit var overturnedApp: String
    private lateinit var humanDeclinedApp: String
    private lateinit var confirmedReview: String
    private lateinit var overturnedReview: String
    private lateinit var successor: String

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
        header("Idempotency-Key", UUID.randomUUID().toString())
    } When {
        post("/api/v1/lending/intake/applications/$applicationId/decline-review")
    }

    private fun decideReview(
        reviewId: String,
        outcome: String,
        reason: String = REASON,
        idempotencyKey: String? = UUID.randomUUID().toString(),
    ) = Given {
        contentType("application/json")
        idempotencyKey?.let { header("Idempotency-Key", it) }
        body("""{"outcome":"$outcome","reason":"$reason"}""")
    } When {
        post("/api/v1/lending/decline-reviews/$reviewId/decision")
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
        confirmedApp = submit(PARTY_A, income = "2000.00")
        overturnedApp = submit(PARTY_B, income = "2000.00")
        humanDeclinedApp = submit(PARTY_C, income = "60000.00")
        listOf(confirmedApp, overturnedApp, humanDeclinedApp).forEach(::advance)

        listOf(confirmedApp, overturnedApp).forEach {
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
    )
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "wire-it-customer-edge-subject"),
            Claim(key = "azp", value = "openbank-customer-edge"),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `3 - the customer asks - a repeat is the same review - a stranger and a human decline are refused`() {
        confirmedReview = customerRequest(PARTY_A, confirmedApp) Then {
            statusCode(201)
            body("status", equalTo("REQUESTED"))
            body("applicationId", equalTo(confirmedApp))
        } Extract { path<String>("id") }

        customerRequest(PARTY_A, confirmedApp) Then {
            statusCode(200)
            body("id", equalTo(confirmedReview))
        }
        assertThat(outboxCount("credit.decline_review.requested", confirmedApp)).isEqualTo(1)

        customerRequest(PARTY_B, confirmedApp) Then { statusCode(404) }

        // The engine-only guard: a decline a person made is never looped back.
        customerRequest(PARTY_C, humanDeclinedApp) Then {
            statusCode(409)
            body("error", equalTo("NOT_ENGINE_DECLINE"))
        }

        overturnedReview =
            customerRequest(PARTY_B, overturnedApp) Then { statusCode(201) } Extract { path<String>("id") }
        assertThat(status(confirmedApp)).isEqualTo("DECLINED")
    }

    @Test
    @Order(4)
    // What the guard really enforces: a reviewer principal NAMED as the applicant's party id. Real staff
    // principals are not party ids and no staff-to-party mapping exists, so a staff member reviewing
    // their own decline as a customer is NOT caught by this service (documented limit, threat model).
    @TestSecurity(user = PARTY_B, roles = ["ROLE_CREDIT_RISK"])
    fun `4 - a reviewer whose principal name is the applicant's party id is refused`() {
        decideReview(overturnedReview, "OVERTURNED") Then {
            statusCode(409)
            body("error", equalTo("SELF_REVIEW"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "dr-it-reviewer", roles = ["ROLE_CREDIT_RISK"])
    fun `5 - a reason is mandatory - confirming keeps the application declined and is final`() {
        decideReview(confirmedReview, "CONFIRMED", reason = "short") Then { statusCode(400) }
        decideReview(confirmedReview, "REQUESTED") Then { statusCode(400) }
        decideReview(confirmedReview, "CONFIRMED", idempotencyKey = null) Then { statusCode(400) }

        decideReview(confirmedReview, "CONFIRMED") Then {
            statusCode(200)
            body("status", equalTo("CONFIRMED"))
            body("reviewedBy", equalTo("dr-it-reviewer"))
        }
        assertThat(status(confirmedApp)).isEqualTo("DECLINED")
        assertThat(outboxCount("credit.decline_review.confirmed", confirmedApp)).isEqualTo(1)

        decideReview(confirmedReview, "OVERTURNED") Then {
            statusCode(409)
            body("error", equalTo("REVIEW_ALREADY_DECIDED"))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "dr-it-reviewer", roles = ["ROLE_CREDIT_RISK"])
    fun `6 - overturning opens a linked successor in FOUR_EYES proposed by the reviewer`() {
        Given { accept("application/json") } When { get("/api/v1/lending/decline-reviews") } Then {
            statusCode(200)
            body("id", org.hamcrest.Matchers.hasItem(overturnedReview))
            body("id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(confirmedReview)))
        }

        successor = decideReview(overturnedReview, "OVERTURNED") Then {
            statusCode(200)
            body("status", equalTo("OVERTURNED"))
        } Extract { path<String>("successorApplicationId") }

        assertThat(status(overturnedApp)).isEqualTo("DECLINED")
        assertThat(status(successor)).isEqualTo("FOUR_EYES")
        assertThat(scalar("select predecessor_application_id::text from loan_application where id = ?", successor))
            .isEqualTo(overturnedApp)
        assertThat(
            scalar("select proposed_by from loan_application where id = ?", successor),
        ).isEqualTo("dr-it-reviewer")
        assertThat(outboxCount("credit.decline_review.overturned", overturnedApp)).isEqualTo(1)
        assertThat(outboxCount("credit.application.transition", successor)).isGreaterThanOrEqualTo(1)
        // Intake's first-due-date rule, recomputed for the successor rather than copied from the decline.
        assertThat(scalar("select first_due_date::text from loan_application where id = ?", successor))
            .isEqualTo(FirstDueDate.forApplicationOn(LocalDate.now(ZoneOffset.UTC)).toString())
        // No fresh engine evaluation: the successor carries no engine outcome of its own.
        assertThat(scalar("select decision_outcome from loan_application where id = ?", successor)).isNull()
    }

    @Test
    @Order(7)
    @TestSecurity(user = "dr-it-reviewer", roles = ["ROLE_CREDIT_RISK"])
    fun `7 - the reviewer cannot also approve the successor`() {
        decideApplication(successor, approve = true) Then {
            statusCode(409)
            body("error", equalTo("SEGREGATION_OF_DUTIES"))
        }
        assertThat(status(successor)).isEqualTo("FOUR_EYES")
    }

    @Test
    @Order(8)
    @TestSecurity(user = "dr-it-approver", roles = ["ROLE_CREDIT_RISK"])
    fun `8 - a different operator approves it through the existing four-eyes decision`() {
        decideApplication(successor, approve = true) Then {
            statusCode(200)
            body("status", equalTo("OFFERED"))
            body("decidedBy", equalTo("dr-it-approver"))
        }
        assertThat(status(overturnedApp)).isEqualTo("DECLINED")
    }

    @Test
    @Order(9)
    @TestSecurity(
        user = EDGE,
        roles = ["ROLE_OPERATOR"],
    )
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "wire-it-customer-edge-subject"),
            Claim(key = "azp", value = "openbank-customer-edge"),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `9 - after a decision a repeat request still answers with the decided review`() {
        customerRequest(PARTY_A, confirmedApp) Then {
            statusCode(200)
            body("status", equalTo("CONFIRMED"))
        }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "some-operator", roles = ["ROLE_OPERATOR"])
    fun `10 - an operator is neither the customer edge nor a reviewer`() {
        customerRequest(PARTY_A, confirmedApp) Then { statusCode(403) }
        decideReview(confirmedReview, "CONFIRMED") Then { statusCode(403) }
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
