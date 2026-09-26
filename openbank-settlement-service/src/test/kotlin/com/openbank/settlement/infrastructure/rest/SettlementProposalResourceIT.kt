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
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.util.UUID
import javax.sql.DataSource

/** Proposal-only semantics must hold even when origination would execute without a checker. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(SettlementOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(SettlementProposalProfile::class)
class SettlementProposalResourceIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "proposal-maker", roles = ["ROLE_OPERATOR"])
    fun `proposal persists reviewable instruction and audit without financial state with enforcement off`() {
        val before = snapshot()
        val request = instruction()
        val response = given().contentType("application/json").body(request)
            .header("X-Approval-Id", UUID.randomUUID().toString())
            .post(BASE).then().statusCode(202).header("Cache-Control", "no-store").extract()
        val id = response.path<String>("id")
        assertThat(response.header("Location")).endsWith("$BASE/$id")
        assertThat(response.path<String>("status")).isEqualTo("PENDING")
        assertThat(response.path<String>("action")).isEqualTo("settlement.create")
        assertThat(response.path<String>("makerId")).isEqualTo("proposal-maker")
        assertThat(response.path<String>("proposalId")).isNotBlank()
        assertThat(response.path<String>("instruction.idempotencyKey")).isEqualTo(request["idempotencyKey"])
        assertThat(response.path<String>("instruction.amount").toBigDecimal()).isEqualByComparingTo("40.1234")
        val detail = given().get("$BASE/$id").then().statusCode(200).extract()
        assertThat(detail.path<String>("settlementCorrelation")).isEqualTo("NOT_OBSERVED")
        assertThat(detail.path<String>("status")).isEqualTo("PENDING")
        val after = snapshot()
        assertThat(after[0]).isEqualTo(before[0] + 1)
        assertThat(after[1]).isEqualTo(before[1] + 1)
        assertThat(after[2]).isEqualTo(before[2])
        assertThat(after[3]).isEqualTo(before[3] + 1)
        assertThat(after[4]).isEqualTo(before[4])
        val spec = javaClass.getResourceAsStream("/openapi.yaml")!!.use { Yaml().load<Map<String, Any>>(it) }
        val operation = ((spec["paths"] as Map<*, *>)[BASE] as Map<*, *>)["post"] as Map<*, *>
        assertThat(operation["operationId"]).isEqualTo("proposeSettlement")
        val responseCodes = (operation["responses"] as Map<*, *>).keys.map { it.toString() }
        assertThat(responseCodes).contains("202", "400", "401", "403")
    }

    @Test
    @TestSecurity(user = "proposal-admin", roles = ["ROLE_ADMIN"])
    fun `administrator can submit a proposal without moving money`() {
        val before = snapshot()[2]
        given().contentType("application/json").body(instruction()).post(BASE).then().statusCode(202)
        assertThat(snapshot()[2]).isEqualTo(before)
    }

    @Test
    @TestSecurity(user = "proposal-maker", roles = ["ROLE_OPERATOR"])
    fun `invalid instruction is rejected before proposal and audit writes`() {
        val before = snapshot()
        given().contentType("application/json").body(instruction() + ("amount" to "0"))
            .post(BASE).then().statusCode(400)
        given().contentType("application/json").body("null").post(BASE).then().statusCode(400)
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `anonymous submission has no effects`() = assertDenied(401)

    @Test
    @TestSecurity(user = "proposal-viewer", roles = ["ROLE_VIEWER"])
    fun `viewer cannot submit`() = assertDenied(403)

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    fun `service account cannot borrow operator proposal permission`() = assertDenied(403)

    private fun assertDenied(status: Int) {
        val before = snapshot()
        given().contentType("application/json").body(instruction()).post(BASE).then().statusCode(status)
        assertThat(snapshot()).isEqualTo(before)
    }

    private fun snapshot(): List<Long> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT (SELECT count(*) FROM settlement_operator_proposals), " +
                    "(SELECT count(*) FROM settlement_operator_approvals), " +
                    "(SELECT count(*) FROM settlements), (SELECT count(*) FROM settlement_outbox), " +
                    "(SELECT count(*) FROM settlement_outbox WHERE event_type = 'SETTLEMENT_STATE_CHANGED')",
            ).use { rows ->
                check(rows.next())
                (1..5).map { rows.getLong(it) }
            }
        }
    }

    private fun instruction(): Map<String, String> = mapOf(
        "idempotencyKey" to "proposal-only-${UUID.randomUUID()}",
        "payerAccountId" to UUID.randomUUID().toString(),
        "payeeAccountId" to UUID.randomUUID().toString(),
        "amount" to "40.1234",
        "currency" to "CZK",
    )

    private companion object {
        const val BASE = "/api/v1/settlements/approvals"
    }
}

class SettlementProposalProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "authz.enforce" to "true",
        "authz.four-eyes.enforce" to "false",
        "quarkus.scheduler.enabled" to "false",
    )
}
