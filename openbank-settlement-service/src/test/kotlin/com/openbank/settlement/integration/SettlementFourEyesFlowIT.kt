// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.integration

import com.openbank.settlement.it.PostgresTestResource
import com.openbank.settlement.it.SettlementOpaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import javax.sql.DataSource

/** Enforced authorization and enforced four-eyes, the generated deployment policy in OPA. */
class SettlementFourEyesProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "authz.enforce" to "true",
        "authz.four-eyes.enforce" to "true",
    )
}

/**
 * The settlement maker/checker journey over real HTTP, real PostgreSQL (V6 durable approvals) and
 * the GENERATED settlement OPA bundle in the deployment's OPA image (#10041 slice 10). Ordered
 * identities drive one approval from PENDING to EXECUTED, and the service accounts that carry
 * ROLE_OPERATOR in the realm are refused the queue by identity.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(SettlementOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(SettlementFourEyesProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SettlementFourEyesFlowIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    @Order(1)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `an operator origination is parked for approval and creates no settlement`() {
        approvalId = given().contentType("application/json").body(instruction).post(SETTLEMENTS)
            .then().statusCode(202)
            .body("status", equalTo("PENDING_APPROVAL")).extract().path("approvalId")
        assertThat(approvalId).isNotBlank()
        assertThat(settlementCount()).isZero()
        assertThat(evidence()).containsExactly("PENDING")
    }

    @Test
    @Order(2)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `a malformed instruction is refused before any approval is parked`() {
        val before = approvalCount()
        given().contentType("application/json").body(instruction + ("amount" to "-1.00")).post(SETTLEMENTS)
            .then().statusCode(400)
        assertThat(approvalCount()).isEqualTo(before)
    }

    @Test
    @Order(3)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `the maker cannot approve their own origination`() {
        requireApproval()
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("$APPROVALS/$approvalId").then().statusCode(403)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    fun `the shared backend service account cannot read or decide the queue`() {
        requireApproval()
        given().get(APPROVALS).then().statusCode(403)
        given().get("$APPROVALS/$approvalId").then().statusCode(403)
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("$APPROVALS/$approvalId").then().statusCode(403)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    fun `the edge service account cannot read or decide the queue`() {
        requireApproval()
        given().get(APPROVALS).then().statusCode(403)
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("$APPROVALS/$approvalId").then().statusCode(403)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "settlement-test-customer", roles = ["ROLE_CUSTOMER"])
    fun `a customer cannot read the queue`() {
        given().get(APPROVALS).then().statusCode(403)
    }

    @Test
    @Order(7)
    @TestSecurity(user = CHECKER, roles = ["ROLE_OPERATOR"])
    fun `a distinct checker reviews the bound instruction and approves it`() {
        requireApproval()
        val pending: List<String> = given().get("$APPROVALS?limit=200").then().statusCode(200).extract().path("id")
        assertThat(pending).contains(approvalId)
        given().get("$APPROVALS/$approvalId").then().statusCode(200)
            .body("status", equalTo("PENDING"))
            .body("makerId", equalTo(MAKER))
            .body("makerActorKind", equalTo("HUMAN"))
            .body("summary", containsString(instruction.getValue("payerAccountId")))
            // Canonical JSON of the bound arguments: the amount renders as a plain number.
            .body("summary", containsString("\"amount\":250"))
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("$APPROVALS/$approvalId").then().statusCode(200)
            .body("status", equalTo("APPROVED"))
            .body("decidedBy", equalTo(CHECKER))
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("$APPROVALS/$approvalId").then().statusCode(409)
    }

    @Test
    @Order(8)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `an approval cannot be borrowed by a different instruction`() {
        requireApproval()
        for (variant in listOf(
            instruction + ("amount" to "250.01"),
            instruction + ("payeeAccountId" to "${UUID.randomUUID()}"),
        )) {
            given().header("X-Approval-Id", approvalId).contentType("application/json").body(variant)
                .post(SETTLEMENTS).then().statusCode(202)
        }
        assertThat(settlementCount()).isZero()
    }

    @Test
    @Order(9)
    @TestSecurity(user = MAKER, roles = ["ROLE_OPERATOR"])
    fun `the identical instruction executes exactly once`() {
        requireApproval()
        given().header("X-Approval-Id", approvalId).contentType("application/json").body(instruction)
            .post(SETTLEMENTS).then().statusCode(201).body("id", notNullValue())
        assertThat(settlementCount()).isEqualTo(1)
        given().header("X-Approval-Id", approvalId).contentType("application/json").body(instruction)
            .post(SETTLEMENTS).then().statusCode(202)
        assertThat(settlementCount()).isEqualTo(1)
    }

    @Test
    @Order(10)
    @TestSecurity(user = CHECKER, roles = ["ROLE_OPERATOR"])
    fun `the consumed approval stays readable as evidence`() {
        requireApproval()
        given().get("$APPROVALS/$approvalId").then().statusCode(200)
            .body("status", equalTo("EXECUTED"))
            .body("decidedBy", equalTo(CHECKER))
            .body("claimedAt", notNullValue())
            .body("expired", equalTo(false))
        given().get("$APPROVALS/${UUID.randomUUID()}").then().statusCode(404)
        assertThat(evidence()).containsExactly("PENDING", "APPROVED", "EXECUTED")
        assertThat(evidenceField("makerActorKind")).containsExactly("HUMAN", "HUMAN", "HUMAN")
        assertThat(evidenceField("schemaVersion")).containsExactly("2", "2", "2")
    }

    private fun settlementCount(): Long = scalar(
        "SELECT count(*) FROM settlements WHERE payer_account_id = ?::uuid",
        instruction.getValue("payerAccountId"),
    )

    private fun approvalCount(): Long = scalar(
        "SELECT count(*) FROM settlement_operator_approvals WHERE maker_id = ?",
        MAKER,
    )

    private fun scalar(sql: String, arg: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { query ->
            query.setString(1, arg)
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    /** Status of each transition's outbox evidence for the approval, in commit order. */
    private fun evidence(): List<String> = evidenceField("status")

    private fun evidenceField(field: String): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT payload::jsonb ->> '$field' FROM settlement_outbox " +
                "WHERE event_type = 'SETTLEMENT_OPERATOR_APPROVAL_CHANGED' AND aggregate_id = ? ORDER BY id",
        ).use { query ->
            query.setObject(1, UUID.fromString(approvalId))
            query.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }
    }

    private fun requireApproval() {
        assertThat(approvalId).describedAs("the maker stage must have created an approval").isNotBlank()
    }

    private companion object {
        const val MAKER = "settlement-test-maker"
        const val CHECKER = "settlement-test-checker"
        const val SETTLEMENTS = "/api/v1/settlements"
        const val APPROVALS = "/api/v1/settlements/approvals"
        var approvalId: String = ""
        val instruction = mapOf(
            "idempotencyKey" to "four-eyes-${UUID.randomUUID()}",
            "payerAccountId" to "${UUID.randomUUID()}",
            "payeeAccountId" to "${UUID.randomUUID()}",
            "amount" to "250.00",
            "currency" to "CZK",
        )
    }
}
