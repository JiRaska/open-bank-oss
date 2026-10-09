// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Drives the real HTTP routes against a real Postgres. Only this shape proves the routes are
 * REGISTERED (#3371), that the entity columns exist (entity-column-names), and that the strategy
 * history is appended rather than overwritten — read back here with plain JDBC.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PensionContractApiIT {

    private val base = "/api/v1/pension/contracts"

    private fun createBody(providerType: String = "PENSION_COMPANY") = """
        {
          "productLine": "DPS",
          "jurisdiction": "CZ",
          "providerEntityId": "${UUID.randomUUID()}",
          "providerType": "$providerType",
          "birthDate": "1985-05-05",
          "residencyCountry": "CZ",
          "schedule": { "amount": 1700, "currency": "CZK", "frequency": "MONTHLY" },
          "strategyCode": "BALANCED",
          "beneficiaries": [ { "name": "Jane Doe", "sharePercent": 100 } ]
        }
    """.trimIndent()

    private fun create(): String = given()
        .contentType("application/json")
        .header("X-Customer-Party-Id", UUID.randomUUID().toString())
        .body(createBody())
        .`when`().post(base)
        .then().statusCode(201)
        .body("status", equalTo("DRAFT"))
        .body("packVersion", equalTo(1))
        .extract().path("contractId")

    private fun post(path: String, body: String = "{}") =
        given().contentType("application/json").body(body).`when`().post(path).then()

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the full participant lifecycle runs over real HTTP`() {
        val id = create()
        post("$base/$id/submit").statusCode(200).body("status", equalTo("PENDING_ACTIVATION"))
        post("$base/$id/activate").statusCode(200).body("status", equalTo("ACTIVE"))

        given().contentType("application/json").body("""{"strategyCode":"DYNAMIC"}""")
            .`when`().put("$base/$id/strategy")
            .then().statusCode(200).body("strategyHistory", hasSize<Any>(2))

        post("$base/$id/suspend").statusCode(200).body("status", equalTo("SUSPENDED"))
        post("$base/$id/resume").statusCode(200).body("status", equalTo("ACTIVE"))

        post("$base/$id/incentive-evaluation", """{"contribution":1000,"period":"MONTH"}""")
            .statusCode(200)
            .body("find { it.incentiveId == 'state-contribution' }.amount", equalTo(200.00f))

        post("$base/$id/early-termination", """{"currentValue":5000,"confirm":true}""")
            .statusCode(200)
            .body("payoutConditionsMet", equalTo(false))
            .body("contract.status", equalTo("TERMINATING"))

        given().`when`().get("$base/$id").then().statusCode(200)
            .body("status", equalTo("TERMINATING"))
            .body("currentStrategy.strategyCode", equalTo("DYNAMIC"))
            .body("beneficiaries[0].name", equalTo("Jane Doe"))

        assertThat(electionRows(UUID.fromString(id))).containsExactly("BALANCED", "DYNAMIC")
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `an illegal transition is a 409 and an unknown contract a 404`() {
        val id = create()
        post("$base/$id/activate").statusCode(409)
        given().`when`().get("$base/${UUID.randomUUID()}").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a missing header or a provider the pack does not permit is a 400, not a 500`() {
        given().contentType("application/json").body(createBody())
            .`when`().post(base).then().statusCode(400)
        given().contentType("application/json")
            .header("X-Customer-Party-Id", UUID.randomUUID().toString())
            .body(createBody(providerType = "BANK"))
            .`when`().post(base).then().statusCode(400)
    }

    private fun electionRows(contractId: UUID): List<String> {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        DriverManager.getConnection(url, "openbank", "openbank_secret").use { conn ->
            conn.prepareStatement(
                "select strategy_code from pension_strategy_elections where contract_id = ? order by id",
            ).use { st ->
                st.setObject(1, contractId)
                st.executeQuery().use { rs ->
                    val out = mutableListOf<String>()
                    while (rs.next()) out += rs.getString(1)
                    return out
                }
            }
        }
    }
}
