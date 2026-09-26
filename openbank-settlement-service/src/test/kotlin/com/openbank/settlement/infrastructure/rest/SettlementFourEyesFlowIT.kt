// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.infrastructure.rest

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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP, bundled OPA policy, PostgreSQL and transactional audit; test identities only. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(SettlementOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(SettlementFourEyesProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SettlementFourEyesFlowIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    @Order(0)
    @TestSecurity(user = "settlement-maker", roles = ["ROLE_OPERATOR"])
    fun `malformed instructions never create an approval`() {
        val before = approvalCount()
        for (body in listOf(
            instruction + ("amount" to 0),
            instruction + ("currency" to "bad"),
            instruction + ("idempotencyKey" to ""),
            instruction + ("payeeAccountId" to instruction.getValue("payerAccountId")),
        )) {
            given().contentType("application/json").body(body).post(BASE).then().statusCode(400)
        }
        given().contentType("application/json").body("null").post(BASE).then().statusCode(400)
        assertThat(approvalCount()).isEqualTo(before)
        assertThat(settlementCount()).isZero()
    }

    @Test
    @Order(1)
    @TestSecurity(user = "settlement-maker", roles = ["ROLE_OPERATOR"])
    fun `origination waits for approval without creating financial state`() {
        approvalId = given().contentType("application/json").body(instruction)
            .post(BASE).then().statusCode(202).extract().path("approvalId")
        assertThat(approvalId).isNotBlank()
        assertThat(settlementCount()).isZero()
        assertThat(status()).isEqualTo("PENDING")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "settlement-maker", roles = ["ROLE_OPERATOR"])
    fun `maker cannot self approve the matching instruction`() {
        decide(instruction, 403)
        assertThat(status()).isEqualTo("PENDING")
        assertThat(settlementCount()).isZero()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "settlement-checker", roles = ["ROLE_OPERATOR"])
    fun `checker must supply the same complete instruction`() {
        given().contentType("application/json").body("{}")
            .patch("$BASE/approvals/$approvalId").then().statusCode(400)
        given().contentType("application/json").body("{\"approve\":null}")
            .patch("$BASE/approvals/$approvalId").then().statusCode(400)
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("$BASE/approvals/$approvalId").then().statusCode(400)
        decide(instruction + ("amount" to 41), 400)
        decide(instruction + ("payeeAccountId" to UUID.randomUUID().toString()), 400)
        assertThat(status()).isEqualTo("PENDING")
        assertThat(settlementCount()).isZero()
    }

    @Test
    @Order(4)
    @TestSecurity(user = "settlement-checker", roles = ["ROLE_OPERATOR"])
    fun `distinct checker approves the reviewed instruction`() {
        decide(instruction, 200)
        assertThat(status()).isEqualTo("APPROVED")
        assertThat(settlementCount()).isZero()
    }

    @Test
    @Order(5)
    @TestSecurity(user = "settlement-maker", roles = ["ROLE_OPERATOR"])
    fun `changed instruction cannot spend the authorization`() {
        given().contentType("application/json").header("X-Approval-Id", approvalId)
            .body(instruction + ("amount" to 41)).post(BASE).then().statusCode(202)
        assertThat(status()).isEqualTo("APPROVED")
        assertThat(settlementCount()).isZero()
    }

    @Test
    @Order(6)
    @TestSecurity(user = "settlement-maker", roles = ["ROLE_OPERATOR"])
    fun `maker spends approval once and retains the complete decision trail`() {
        given().contentType("application/json").header("X-Approval-Id", approvalId)
            .body(instruction).post(BASE).then().statusCode(201)
        assertThat(status()).isEqualTo("EXECUTED")
        assertThat(settlementCount()).isEqualTo(1)
        val states = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT payload::jsonb->>'status' FROM settlement_outbox WHERE aggregate_id = ? ORDER BY id",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(approvalId))
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        assertThat(states).containsExactly("PENDING", "APPROVED", "EXECUTED")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "settlement-maker", roles = ["ROLE_OPERATOR"])
    fun `spent approval cannot authorize a second execution`() {
        given().contentType("application/json").header("X-Approval-Id", approvalId)
            .body(instruction).post(BASE).then().statusCode(202)
        assertThat(settlementCount()).isEqualTo(1)
    }

    private fun decide(reviewed: Map<String, Any>, expected: Int) {
        given().contentType("application/json").body(mapOf("approve" to true, "instruction" to reviewed))
            .patch("$BASE/approvals/$approvalId").then().statusCode(expected)
    }

    private fun approvalCount(): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM settlement_operator_approvals").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun status(): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status FROM settlement_operator_approvals WHERE id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(approvalId))
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun settlementCount(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM settlements WHERE payer_account_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(instruction.getValue("payerAccountId") as String))
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private companion object {
        const val BASE = "/api/v1/settlements"
        var approvalId = ""
        val instruction: Map<String, Any> = mapOf(
            "idempotencyKey" to "approval-proof-${UUID.randomUUID()}",
            "payerAccountId" to UUID.randomUUID().toString(),
            "payeeAccountId" to UUID.randomUUID().toString(),
            "amount" to 40,
            "currency" to "CZK",
        )
    }
}

class SettlementFourEyesProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "authz.enforce" to "true",
        "authz.four-eyes.enforce" to "true",
        "quarkus.scheduler.enabled" to "false",
    )
}
