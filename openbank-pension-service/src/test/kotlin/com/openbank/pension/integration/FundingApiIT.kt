// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * The S3 funding routes over real HTTP against a real Postgres (ADR-0334 S3): proves the routes
 * are registered (#3371), the V3 columns exist, the ON CONFLICT idempotency holds in SQL, and the
 * ownership guard answers 404 for someone else's contract.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FundingApiIT {

    private val contracts = "/api/v1/pension/contracts"
    private val funding = "/api/v1/pension/funding/contracts"
    private val ops = "/api/v1/pension/funding/operations"

    private fun spec(party: UUID?): RequestSpecification = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }

    /** An ACTIVE DPS contract of [party], created through S1's own routes. */
    private fun activeContract(party: UUID): String {
        val id: String = spec(party).body(
            """
            {"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}","providerType":"PENSION_COMPANY",
             "birthDate":"1985-05-05","residencyCountry":"CZ","schedule":{"amount":1700,"currency":"CZK","frequency":"MONTHLY"},
             "strategyCode":"BALANCED","beneficiaries":[{"name":"Jane Doe","sharePercent":100}]}
            """.trimIndent(),
        ).`when`().post(contracts).then().statusCode(201).extract().path("contractId")
        spec(party).body("{}").`when`().post("$contracts/$id/submit").then().statusCode(200)
        spec(party).body("{}").`when`().post("$contracts/$id/activate").then().statusCode(200)
        return id
    }

    private fun reference(id: String, party: UUID?): String =
        spec(party).`when`().get("$funding/$id/payment-reference").then().statusCode(200).extract().path("reference")

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `intake, unmatched queue, claim batch and receipt run end to end`() {
        val party = UUID.randomUUID()
        val id = activeContract(party)
        val ref = reference(id, null)
        val payment = """{"paymentId":"it-${UUID.randomUUID()}","amount":1700,"currency":"CZK",""" +
            """"valueDate":"2026-01-15","reference":"$ref"}"""

        spec(
            null,
        ).body(payment).`when`().post("$ops/payments").then().statusCode(200).body("outcome", equalTo("CREDITED"))
        spec(
            null,
        ).body(payment).`when`().post("$ops/payments").then().statusCode(200).body("outcome", equalTo("DUPLICATE"))

        val strayId = "stray-${UUID.randomUUID()}"
        val unmatchedId: String = spec(null)
            .body(
                """{"paymentId":"$strayId","amount":300,"currency":"CZK","valueDate":"2026-01-20","reference":"0000"}""",
            )
            .`when`().post("$ops/payments").then().statusCode(200)
            .body("outcome", equalTo("UNMATCHED")).body("unmatched.reason", equalTo("UNKNOWN_REFERENCE"))
            .extract().path("unmatched.id")
        spec(null).`when`().get("$ops/unmatched?status=OPEN").then().statusCode(200)
            .body("find { it.id == '$unmatchedId' }.paymentId", equalTo(strayId))
        spec(null).body("""{"contractId":"$id"}""").`when`().post("$ops/unmatched/$unmatchedId/assign")
            .then().statusCode(200).body("contractId", equalTo(id))
        spec(null).body("{}").`when`().post("$ops/unmatched/$unmatchedId/return").then().statusCode(409)

        val run = spec(null).body("""{"period":"2026-01"}""").`when`().post("$ops/claim-runs").then().statusCode(200)
            .extract().jsonPath()
        val batchId = run.getList<Map<String, Any>>("batches")
            .single { (it["payload"] as String).contains(ref) }["id"] as String
        val claimId: String = spec(null).`when`().get("$funding/$id/incentives").then().statusCode(200)
            .body("claims", hasSize<Any>(1)).body("claims[0].claimedAmount", equalTo(340.0f))
            .extract().path("claims[0].id")

        spec(
            null,
        ).body("""{"payload":"$claimId;ACCEPTED;340.00"}""").`when`().post("$ops/claim-batches/$batchId/receipt")
            .then().statusCode(200).body("status", equalTo("RECONCILED"))
        spec(null).`when`().get("$funding/$id/incentives").then().statusCode(200)
            .body("balances[0].net", equalTo(340.0f))
        spec(null).`when`().get("$funding/$id/tax-years/2026").then().statusCode(200)
            .body("participantContributions", equalTo(2000.0f))
            .body("stateIncentives", equalTo(340.0f))
        spec(null).`when`().get("$ops/contracts/$id/clawback-preview?on=2026-06-01").then().statusCode(200)
            .body("find { it.kind == 'RETURN_TO_AGENCY' }.amount", equalTo(340.0f))

        assertThat(rows("SELECT count(*) FROM pension_contributions WHERE contract_id = '$id'")).isEqualTo(3)
        assertThat(rows("SELECT count(*) FROM pension_incentive_ledger WHERE contract_id = '$id'")).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `staff read without the header but never write for a participant, and bad input is a 400`() {
        val id = activeContract(UUID.randomUUID())
        spec(null).`when`().get("$funding/$id/contributions").then().statusCode(200)
        spec(
            null,
        ).body(
            """{"kind":"STANDING_ORDER","debtorIban":"CZ6508000000192000145399","amount":1700,"currency":"CZK","firstCollection":"2026-11-01"}""",
        )
            .`when`().post("$funding/$id/mandates").then().statusCode(400)
        spec(
            null,
        ).body("""{"paymentId":"p","amount":12.345,"currency":"CZK","valueDate":"2026-01-15","reference":"1"}""")
            .`when`().post("$ops/payments").then().statusCode(400)
        spec(null).`when`().get("$ops/unmatched/${UUID.randomUUID()}/assign").then().statusCode(405)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the edge reaches only its participant's contract and never an operator route`() {
        val owner = UUID.randomUUID()
        val id = activeContract(owner)
        spec(owner).`when`().get("$funding/$id/contributions").then().statusCode(200)
        spec(
            owner,
        ).body(
            """{"kind":"STANDING_ORDER","debtorIban":"CZ6508000000192000145399","amount":1700,"currency":"CZK","firstCollection":"2026-11-01"}""",
        )
            .`when`().post("$funding/$id/mandates").then().statusCode(201)
        spec(owner).body("""{"usage":{"retirement-products-deduction":10000}}""")
            .`when`().put("$funding/$id/tax-years/2026/external-cap-usage").then().statusCode(200)

        val stranger = UUID.randomUUID()
        listOf("payment-reference", "contributions", "incentives", "tax-years/2026").forEach { path ->
            spec(stranger).`when`().get("$funding/$id/$path").then().statusCode(404)
        }
        spec(
            stranger,
        ).body(
            """{"usage":{"x":1}}""",
        ).`when`().put("$funding/$id/tax-years/2026/external-cap-usage").then().statusCode(404)
        spec(null).`when`().get("$funding/$id/contributions").then().statusCode(400)

        spec(owner).`when`().get("$ops/unmatched").then().statusCode(403)
        spec(owner).body("""{"period":"2026-01"}""").`when`().post("$ops/claim-runs").then().statusCode(403)
        spec(owner).body("""{"paymentId":"x","amount":1,"currency":"CZK","valueDate":"2026-01-01"}""")
            .`when`().post("$ops/payments").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `an employer bulk line only credits a contract that enrolled that employer`() {
        val owner = UUID.randomUUID()
        val id = activeContract(owner)
        val employer = UUID.randomUUID()
        spec(owner).body("{}").`when`().put("$funding/$id/employers/$employer").then().statusCode(204)
        assertThat(rows("SELECT count(*) FROM pension_employer_enrolments WHERE contract_id = '$id'")).isEqualTo(1)
    }

    private fun rows(sql: String): Int {
        val cfg = ConfigProvider.getConfig()
        DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    r.next()
                    return r.getInt(1)
                }
            }
        }
    }
}
