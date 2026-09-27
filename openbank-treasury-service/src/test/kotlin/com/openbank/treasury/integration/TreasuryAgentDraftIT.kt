// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0315 D10, run AS the agent. `agent:treasury-dealing-assistant` is the principal name the
 * treasury-dealing-assistant charter authenticates as (the `agent:` prefix is what classifies it
 * AI_AGENT, in libs' AuthorizeInterceptor and in [com.openbank.treasury.domain.model.Actor]).
 * It is given the dealer realm role — the most a treasury agent client could be handed — so what
 * refuses it below is the service, not a missing role on the token.
 *
 * The agent drafts; everything that books, submits or overrides is refused at the service layer
 * (RBAC for approver/senior actions, the domain's human-only rule for submit). A human dealer then
 * submits the agent's draft and a different human books it: four-eyes unchanged. OPA refuses the
 * same actions one layer out (treasury_rest_ext_test.rego); this proves the layer that holds when
 * `AUTHZ_ENFORCE` is off.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryAgentDraftIT {

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun body(rationale: String? = RATIONALE, inputs: String? = INPUTS): String {
        val extra = listOfNotNull(
            rationale?.let { "\"rationale\":\"$it\"" },
            inputs?.let { "\"inputs\":$it" },
        ).joinToString(",", prefix = if (rationale == null && inputs == null) "" else ",")
        return """
            {"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":250000.00,
             "rate":4.10,"valueDate":"$today","maturityDate":"${today.plusDays(7)}"$extra}
        """.trimIndent()
    }

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    @Test
    @Order(1)
    @TestSecurity(user = AGENT, roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - the agent drafts a deal that stores its rationale and inputs and consumes no limit`() {
        draftId = post("/deals", body()).then().statusCode(201)
            .body("state", equalTo("DRAFT"))
            .body("createdBy", equalTo(AGENT))
            .body("createdByType", equalTo("AI_AGENT"))
            .body("rationale", equalTo(RATIONALE))
            .body("limitCheck", nullValue())
            .extract().path("dealId")
        val (rationale, inputs) = jdbc { c ->
            c.prepareStatement("SELECT rationale, draft_inputs FROM deals WHERE deal_id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(draftId))
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "no row for the agent's draft" }
                    rs.getString(1) to rs.getString(2)
                }
            }
        }
        assertThat(rationale).isEqualTo(RATIONALE)
        assertThat(inputs).contains("\"quote\"").contains("\"headroom\"")
    }

    @Test
    @Order(2)
    @TestSecurity(user = AGENT, roles = ["ROLE_TREASURY_DEALER"])
    fun `2 - an agent draft without rationale or inputs is refused`() {
        post("/deals", body(rationale = null)).then().statusCode(400)
        post("/deals", body(inputs = null)).then().statusCode(400)
        post("/deals", body(inputs = "\"just a string\"")).then().statusCode(400)
    }

    @Test
    @Order(3)
    @TestSecurity(user = AGENT, roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - the agent can never submit, approve, override, settle or reverse`() {
        // submit: the dealer role lets the request in; the domain refuses a non-human.
        post("/deals/$draftId/submit").then().statusCode(403).body("error", equalTo("ACTOR_NOT_PERMITTED"))
        post("/deals/$draftId/approve").then().statusCode(403)
        post("/deals/$draftId/override-limit", """{"reason":"trust me"}""").then().statusCode(403)
        post("/deals/$draftId/settle").then().statusCode(403)
        post("/deals/$draftId/reverse", """{"reason":"x"}""").then().statusCode(403)
        assertThat(state(draftId)).isEqualTo("DRAFT")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "harry.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `4 - a human dealer submits the agent's draft`() {
        post("/deals/$draftId/submit").then().statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("submittedBy", equalTo("harry.dealer"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "harry.dealer", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - the submitter still cannot book it - four-eyes is unchanged`() {
        post("/deals/$draftId/approve").then().statusCode(422)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "ada.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - a second human books it, and the agent's inputs are still on the record`() {
        post("/deals/$draftId/approve").then().statusCode(200).body("state", equalTo("BOOKED"))
        given().`when`().get("/api/v1/treasury/deals/$draftId").then().statusCode(200)
            .body("createdByType", equalTo("AI_AGENT"))
            .body("rationale", equalTo(RATIONALE))
    }

    private fun state(id: String): String = jdbc { c ->
        c.prepareStatement("SELECT state FROM deals WHERE deal_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    companion object {
        const val AGENT = "agent:treasury-dealing-assistant"
        const val RATIONALE = "SIMBK-A quotes 4.10 for 1W, 15 bp over the curve; 2.1bn CZK headroom"
        const val INPUTS = """{"quote":{"counterparty":"SIMBK-A","rate":"4.10","tenor":"1W"},"headroom":"2.1e9"}"""
        lateinit var draftId: String
    }
}
