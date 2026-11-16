// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * The settlement operator journey over the real HTTP surface: an operator originates an
 * instruction, watches it read back PENDING, confirms the idempotent replay writes exactly one
 * row, drives the lifecycle DEBITED -> CREDITED -> BOOKED through the read route, and meets the
 * fail-closed edges (malformed instruction, non-canonical id, unknown id, service account,
 * viewer).
 *
 * Ordered, because the lifecycle is a state machine — each step is only observable from the
 * previous one.
 *
 * The module's ITs prove one property each (four-eyes enforcement, audit atomicity, reversal
 * wire shape); this proves the operator-visible route sequence serves the whole originate ->
 * observe -> progress -> read-back story over HTTP, so a wiring regression that deregisters a
 * route (#3371), breaks the deterministic idempotency id, or maps a status wrong fails here even
 * when every property-level assertion stays green.
 *
 * The Temporal workflow is dispatched to the module's in-process `TestWorkflowEnvironment`
 * (`WorkflowClientTestProducer`, the established test wiring); the lifecycle transitions are
 * applied through the module's own test-only status boundary, which is the same Vert.x-carrying
 * HTTP path the audit ITs drive. Everything else — validation, idempotency, persistence, status
 * mapping — is the real, CDI-wired code.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_settlement_e2e")],
)
@TestProfile(SettlementLifecycleE2E.JourneyProfile::class)
class SettlementLifecycleE2E {

    /** Own boot (own container DB), mirroring the ITs' isolation. */
    class JourneyProfile : QuarkusTestProfile

    @Inject
    lateinit var dataSource: DataSource

    private val payer = UUID.randomUUID()
    private val payee = UUID.randomUUID()
    private val idempotencyKey = UUID.randomUUID().toString()

    private var settlementId: String = ""

    @Test
    @Order(1)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `1 - the operator originates a settlement and reads it back PENDING`() {
        val response = given().contentType("application/json").body(instruction())
            .post(SETTLEMENTS).then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("payerAccountId", equalTo(payer.toString()))
            .body("payeeAccountId", equalTo(payee.toString()))
            .body("currency", equalTo("CZK"))
            .body("status", equalTo("PENDING"))
            .extract()
        settlementId = response.path("id")
        // The parser's number type (Float vs BigDecimal) is an implementation detail; the wire
        // value is what the contract promises. numeric(19,4) scale must not flake it either.
        assertThat(BigDecimal(response.path<Number>("amount").toString())).isEqualByComparingTo("250.00")

        // The read-back route — not the origination response — is the observable: persisted
        // state, exact decimal text, no-store header semantics.
        given().get("$SETTLEMENTS/$settlementId").then()
            .statusCode(200)
            .body("id", equalTo(settlementId))
            // Exact decimal text of the persisted numeric(19,4) value — the read contract.
            .body("amount", equalTo("250.0000"))
            .body("currency", equalTo("CZK"))
            .body("status", equalTo("PENDING"))
            .body("recoveryRequired", equalTo(false))
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `2 - an identical retry replays the same settlement, no second write`() {
        // The id is derived deterministically from the idempotency key, so the retried
        // instruction resolves to the same row.
        given().contentType("application/json").body(instruction())
            .post(SETTLEMENTS).then()
            .statusCode(201)
            .body("id", equalTo(settlementId))

        assertThat(settlementCount(settlementId)).isEqualTo(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `3 - the lifecycle progresses DEBITED to CREDITED to BOOKED through the read route`() {
        // The status boundary is the module's own test-only Vert.x-carrying path; each
        // transition is confirmed through the operator read route, never through the write
        // response alone.
        for (status in listOf("DEBITED", "CREDITED", "BOOKED")) {
            given().post("$TEST_AUDIT/$settlementId/status/$status").then().statusCode(200)
            given().get("$SETTLEMENTS/$settlementId").then()
                .statusCode(200)
                .body("status", equalTo(status))
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `4 - malformed instructions are refused before anything is written`() {
        val before = settlementCount()

        given().contentType("application/json")
            .body(instruction(key = UUID.randomUUID().toString(), amount = "-1.00"))
            .post(SETTLEMENTS).then().statusCode(400)
        given().contentType("application/json")
            .body(instruction(key = UUID.randomUUID().toString(), payee = payer))
            .post(SETTLEMENTS).then().statusCode(400)
        given().contentType("application/json")
            .body(instruction(key = UUID.randomUUID().toString(), currency = "Czk"))
            .post(SETTLEMENTS).then().statusCode(400)

        assertThat(settlementCount()).isEqualTo(before)
    }

    @Test
    @Order(5)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `5 - the read surface fails closed on bad ids and unknown settlements`() {
        // Not a canonical UUID: rejected by the route's own parser, not a 404 of last resort.
        given().get("$SETTLEMENTS/not-a-uuid").then().statusCode(400)
        given().get("$SETTLEMENTS/${UUID.randomUUID()}").then().statusCode(404)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "service-account-settlement-e2e", roles = ["ROLE_OPERATOR"])
    fun `6 - a service account is refused the human operator read`() {
        given().get("$SETTLEMENTS/$settlementId").then().statusCode(403)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "settlement-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `7 - a viewer cannot originate`() {
        given().contentType("application/json").body(instruction(key = UUID.randomUUID().toString()))
            .post(SETTLEMENTS).then().statusCode(403)
    }

    private fun instruction(
        key: String = idempotencyKey,
        payer: UUID = this.payer,
        payee: UUID = this.payee,
        amount: String = "250.00",
        currency: String = "CZK",
    ) = mapOf(
        "idempotencyKey" to key,
        "payerAccountId" to payer,
        "payeeAccountId" to payee,
        "amount" to amount,
        "currency" to currency,
    )

    private fun settlementCount(id: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM settlements WHERE id = ?").use { query ->
            query.setObject(1, UUID.fromString(id))
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun settlementCount(): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM settlements").use { query ->
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private companion object {
        const val OPERATOR = "settlement-e2e-operator"
        const val SETTLEMENTS = "/api/v1/settlements"
        const val TEST_AUDIT = "/test/settlement-audit"
    }
}
