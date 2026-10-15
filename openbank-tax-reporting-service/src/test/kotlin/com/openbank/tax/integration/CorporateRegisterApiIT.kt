// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.path.json.config.JsonPathConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.UUID

/**
 * The corporate register over real HTTP and Postgres (#12425): a maker proposes, a DIFFERENT
 * authenticated checker approves (separate ordered methods, each its own @TestSecurity), and only
 * then do ČNB PSP 32-04, 50-04 and 40-01 assemble — from the approved figures, never a proposal.
 * The database refuses an edit or delete of a decided row whatever the application does.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_corporate_register_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@QuarkusTestResource(PensionProvidersStub::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CorporateRegisterApiIT {
    companion object {
        const val PATH = "/api/v1/corporate-register"
        val proposed = mutableListOf<String>()
        lateinit var superseded: String
    }

    private fun propose(fact: String, value: String, effective: String) = given().contentType("application/json")
        .body(
            """{"entityId":"company","fact":"$fact","value":$value,"effectiveFrom":"$effective",
                "reason":"annual update","evidence":"general meeting minutes 2025-04-30"}""",
        ).post(PATH)

    private fun assemble(code: String, period: String) = given().contentType("application/json")
        .body("""{"catalogueId":"cz-pension-cnb","returnCode":"$code","entityId":"company","period":"$period"}""")
        .post("/api/v1/statutory-returns/assemble")

    private fun datapoints(code: String, period: String): Map<String, BigDecimal> {
        val body = assemble(code, period).then().log().ifValidationFails().statusCode(200).extract().asString()
        return JsonPath(body).using(JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .getMap<String, Any>("datapoints").mapValues { BigDecimal(it.value.toString()) }
    }

    @Test
    @Order(1)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `a maker proposes figures, which feed no return until approved`() {
        listOf(
            Triple("REGULATORY_CAPITAL", "62000000", "2009-01-01"),
            Triple("CAPITAL_REQUIREMENT", "50000000", "2009-01-01"),
            Triple("SHARE_CAPITAL", "50000000", "2009-01-01"),
            Triple("EMPLOYEES_COUNT", "42", "2009-01-01"),
            Triple("QUALIFYING_SHAREHOLDERS_COUNT", "1", "2009-01-01"),
            Triple("BOARD_OF_DIRECTORS_MEMBERS", "3", "2009-01-01"),
            Triple("SUPERVISORY_BOARD_MEMBERS", "3", "2009-01-01"),
            Triple("DIVIDEND_PAID_OR_PLANNED", "0", "2009-04-30"),
        ).forEach { (fact, value, effective) ->
            proposed += propose(fact, value, effective).then().statusCode(201)
                .body("status", equalTo("PROPOSED")).body("version", equalTo(1)).extract().path<String>("id")
        }
        // A second version for the same date supersedes the first once approved.
        superseded = proposed.first()
        proposed += propose("REGULATORY_CAPITAL", "61000000", "2009-01-01").then().statusCode(201)
            .body("version", equalTo(2)).extract().path<String>("id")

        // Proposals feed nothing: the return names what is missing.
        assemble("PSP32-04", "2009-Q1").then().statusCode(503).body(containsString("REGULATORY_CAPITAL"))
        // The maker cannot be the checker.
        given().post("$PATH/${proposed.last()}/approve").then().statusCode(409).body(containsString("Four-eyes"))
        // Validation: a negative figure and a fractional count are 422; a missing field 400; a foreign entity 422.
        propose("SHARE_CAPITAL", "-1", "2009-01-01").then().statusCode(422)
        propose("EMPLOYEES_COUNT", "1.5", "2009-01-01").then().statusCode(422)
        given().contentType("application/json").body("""{"entityId":"company"}""").post(PATH).then().statusCode(400)
        given().contentType("application/json").body(
            """{"entityId":"other","fact":"SHARE_CAPITAL","value":1,"effectiveFrom":"2009-01-01","reason":"r","evidence":"e"}""",
        ).post(PATH).then().statusCode(422)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `a checker approves, and the ČNB company returns assemble from the approved figures`() {
        proposed.forEach { id ->
            given().post("$PATH/$id/approve").then().statusCode(200)
                .body("status", equalTo("APPROVED")).body("decidedBy", equalTo("checker"))
        }
        given().post("$PATH/${proposed.first()}/reject").then().statusCode(409)
        given().post("$PATH/${UUID.randomUUID()}/approve").then().statusCode(404)

        val capital = datapoints("PSP32-04", "2009-Q1")
        assertThat(capital.getValue("capital")).isEqualByComparingTo("61000000") // version 2 wins
        assertThat(capital.getValue("capital_requirement")).isEqualByComparingTo("50000000")
        assertThat(capital.getValue("capital_surplus")).isEqualByComparingTo("11000000")

        val organisation = datapoints("PSP50-04", "2009-Q1")
        assertThat(organisation.getValue("share_capital")).isEqualByComparingTo("50000000")
        assertThat(organisation.getValue("employees_count")).isEqualByComparingTo("42")
        assertThat(organisation.getValue("qualifying_shareholders_count")).isEqualByComparingTo("1")
        assertThat(organisation.getValue("board_of_directors_members")).isEqualByComparingTo("3")
        assertThat(organisation.getValue("supervisory_board_members")).isEqualByComparingTo("3")

        val risk = datapoints("PSP40-01", "2009")
        assertThat(risk.getValue("dividend_paid_or_planned")).isEqualByComparingTo("0")
        assertThat(risk.getValue("contributions_received_year")).isEqualByComparingTo("3030.00")
        assertThat(risk.getValue("payouts_paid_year")).isEqualByComparingTo("125000.00")

        // A dividend effective in 2009 is not 2010's: that year has none approved.
        given().get("$PATH/effective?entityId=company&periodStart=2010-01-01&periodEnd=2010-12-31").then()
            .statusCode(200).body("DIVIDEND_PAID_OR_PLANNED", equalTo(null))
            .body("SHARE_CAPITAL", equalTo(50000000.0f))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "auditor", roles = ["ROLE_AUDITOR"])
    fun `an auditor reads the trail, cannot write, and the database refuses to rewrite a decided row`() {
        given().get("$PATH?entityId=company").then().statusCode(200)
            .body("size()", equalTo(9))
            .body("find { it.id == '$superseded' }.proposedBy", equalTo("maker"))
            .body("find { it.id == '$superseded' }.decidedBy", equalTo("checker"))
        given().get(PATH).then().statusCode(400)
        propose("SHARE_CAPITAL", "1", "2009-01-01").then().statusCode(403)

        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            assertThatThrownBy {
                c.createStatement().use { it.executeUpdate("update corporate_register_entry set value = 1") }
            }.hasMessageContaining("only the decision of a PROPOSED entry may change")
            assertThatThrownBy {
                c.createStatement().use { it.executeUpdate("delete from corporate_register_entry") }
            }.hasMessageContaining("append-only")
            assertThatThrownBy {
                c.createStatement().use {
                    it.executeUpdate(
                        "insert into corporate_register_entry (id, entity_id, fact, value, effective_from, version, " +
                            "reason, evidence, proposed_by, proposed_at, status, decided_by, decided_at) values " +
                            "('${UUID.randomUUID()}', 'company', 'SHARE_CAPITAL', 1, '2009-01-01', 9, 'r', 'e', " +
                            "'same', now(), 'APPROVED', 'same', now())",
                    )
                }
            }.hasMessageContaining("ck_corporate_register_four_eyes")
        }
    }
}
