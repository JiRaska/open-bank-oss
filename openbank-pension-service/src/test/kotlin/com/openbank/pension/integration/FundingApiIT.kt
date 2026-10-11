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

    private val funding = "/api/v2/pension/funding/contracts"
    private val ops = "/api/v2/pension/funding/operations"

    private fun spec(party: UUID?): RequestSpecification = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }

    /**
     * An ACTIVE contract written straight to S1's table, for staff tests: staff may not create a
     * contract over HTTP (writes act for a participant, and only the edge relay may name one).
     */
    private fun seededContract(party: UUID): String {
        val id = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                // An explicit id far above Hibernate's pooled-sequence range: the BIGSERIAL default and
                // the entity's `pension_contracts_seq` blocks are different sequences and collide.
                "INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction, " +
                    "pack_version, " +
                    "provider_entity_id, provider_type, participant_birth_date, status, contribution_amount, " +
                    "contribution_currency, contribution_frequency, start_date, created_at, updated_at) " +
                    "VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 2, ?, " +
                    "'PENSION_COMPANY', DATE '1985-05-05', 'ACTIVE', 1700, 'CZK', " +
                    "'MONTHLY', DATE '2025-01-01', now(), now())",
            ).use { st ->
                st.setObject(1, id)
                st.setObject(2, party)
                st.setObject(3, UUID.randomUUID())
                st.executeUpdate()
            }
            // The aggregate requires a strategy; S1 rehydrates the contract through it.
            c.prepareStatement(
                "INSERT INTO pension_strategy_elections (id, contract_id, strategy_code, effective_from, elected_at) " +
                    "VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, 'BALANCED', DATE '2025-01-01', now())",
            ).use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
        }
        return id.toString()
    }

    private fun reference(id: String, party: UUID?): String =
        spec(party).`when`().get("$funding/$id/payment-reference").then().statusCode(200).extract().path("reference")

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `intake, unmatched queue, claim batch and receipt run end to end`() {
        val party = UUID.randomUUID()
        val id = seededContract(party)
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
        ).body(
            """{"payload":"R;cz-mf-state-contribution-v1;RESULT;2026;1;340.00\nP;$claimId;340.00"}""",
        ).`when`().post("$ops/claim-batches/$batchId/receipt")
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
        val id = seededContract(UUID.randomUUID())
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
        val id = seededContract(owner)
        spec(owner).`when`().get("$funding/$id/contributions").then().statusCode(200)
        spec(
            owner,
        ).body(
            """{"kind":"STANDING_ORDER","debtorIban":"CZ6508000000192000145399","amount":1700,"currency":"CZK","firstCollection":"2026-11-01","scaChallengeId":"sca-${UUID.randomUUID()}"}""",
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
        val id = seededContract(owner)
        val employer = UUID.randomUUID()
        // Binding an employer to the contract is SCA-bound like every sibling: no challenge, 403.
        spec(owner).body("{}")
            .`when`().put("$funding/$id/employers/$employer").then().statusCode(403)
        assertThat(rows("SELECT count(*) FROM pension_employer_enrolments WHERE contract_id = '$id'")).isEqualTo(0)
        spec(owner)
            .body("""{"scaChallengeId":"sca-${UUID.randomUUID()}"}""")
            .`when`().put("$funding/$id/employers/$employer").then().statusCode(204)
        assertThat(rows("SELECT count(*) FROM pension_employer_enrolments WHERE contract_id = '$id'")).isEqualTo(1)
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private fun rows(sql: String): Int = jdbc { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { r ->
                r.next()
                r.getInt(1)
            }
        }
    }

    @Test
    @TestSecurity(user = "mallory", roles = ["ROLE_API"])
    fun `a party header from anyone but the edge relay is refused`() {
        val id = seededContract(UUID.randomUUID())
        spec(UUID.randomUUID()).`when`().get("$funding/$id/contributions").then().statusCode(403)
    }
}
