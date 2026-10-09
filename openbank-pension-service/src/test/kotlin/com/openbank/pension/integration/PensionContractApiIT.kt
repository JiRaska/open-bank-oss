// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
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
    private val party: UUID = UUID.randomUUID()

    private fun createBody(providerType: String = "PENSION_COMPANY") = """
        {
          "productLine": "DPS",
          "jurisdiction": "CZ",
          "providerEntityId": "${UUID.randomUUID()}",
          "providerType": "$providerType",
          "birthDate": "1985-05-05",
          "residencyCountry": "CZ",
          "schedule": { "amount": 1700, "currency": "CZK", "frequency": "MONTHLY" },
          "strategyCode": "CONSERVATIVE",
          "beneficiaries": [ { "name": "Jane Doe", "sharePercent": 100 } ]
        }
    """.trimIndent()

    private fun create(key: String = UUID.randomUUID().toString()): String = given()
        .contentType("application/json")
        .header("X-Customer-Party-Id", party.toString())
        .header("Idempotency-Key", key)
        .body(createBody())
        .`when`().post(base)
        .then().statusCode(201)
        .body("status", equalTo("DRAFT"))
        .body("packVersion", equalTo(2))
        .extract().path("contractId")

    private fun post(path: String, body: String = "{}", asParty: UUID? = party) =
        given().contentType("application/json")
            .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(body).`when`().post(path).then()

    private fun read(id: String, asParty: UUID?) =
        given().apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
            .`when`().get("$base/$id").then()

    @Inject
    lateinit var contracts: PensionContractRepository

    private fun <T> onVertx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    /** Fixture: the product activates only through onboarding (S8), which this class does not drive. */
    private fun activateDirectly(id: String) = onVertx {
        val pending = requireNotNull(contracts.findById(UUID.fromString(id)))
        contracts.save(pending.activate(LocalDate.now(), Instant.now()))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the participant cannot activate or terminate through the retired S1 routes`() {
        val id = create()
        post("$base/$id/submit").statusCode(200).body("status", equalTo("PENDING_ACTIVATION"))
        // ADR-0334 S8: activation is the onboarding workflow's (KID, SCA, cooling-off); termination
        // is S5's quote/sign flow. Neither S1 shortcut is served any more.
        post("$base/$id/activate").statusCode(404)
        post("$base/$id/early-termination", """{"currentValue":5000,"confirm":true}""").statusCode(404)
        read(id, party).statusCode(200).body("status", equalTo("PENDING_ACTIVATION"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the participant lists exactly its own contracts`() {
        val mine = create()
        val stranger = UUID.randomUUID()
        given().header("X-Customer-Party-Id", party.toString()).`when`().get(base).then().statusCode(200)
            .body("contractId", hasItem(mine))
            .body("participantPartyId", everyItem(equalTo(party.toString())))
        given().header("X-Customer-Party-Id", stranger.toString()).`when`().get(base).then().statusCode(200)
            .body("contractId", not(hasItem(mine)))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the retired S1 create route cannot open a contract in a riskier strategy than the most conservative`() {
        given().contentType("application/json").header("X-Customer-Party-Id", party.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(createBody().replace("\"CONSERVATIVE\"", "\"DYNAMIC\""))
            .`when`().post(base)
            .then().statusCode(403).body("code", equalTo("STRATEGY_NOT_PERMITTED"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the full participant lifecycle runs over real HTTP`() {
        val id = create()
        post("$base/$id/submit").statusCode(200).body("status", equalTo("PENDING_ACTIVATION"))
        activateDirectly(id)

        // S1 contract: no suitability assessment exists, so a riskier strategy is refused at the
        // use case (the same gate as onboarding), even with a valid challenge and key.
        given().contentType("application/json").header("X-Customer-Party-Id", party.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"strategyCode":"DYNAMIC","scaChallengeId":"sca-${UUID.randomUUID()}"}""")
            .`when`().put("$base/$id/strategy")
            .then().statusCode(403).body("code", equalTo("STRATEGY_NOT_PERMITTED"))

        post("$base/$id/suspend").statusCode(200).body("status", equalTo("SUSPENDED"))
        post("$base/$id/resume").statusCode(200).body("status", equalTo("ACTIVE"))

        post("$base/$id/incentive-evaluation", """{"contribution":1000,"period":"MONTH"}""")
            .statusCode(200)
            .body("find { it.incentiveId == 'state-contribution' }.amount", equalTo(200.00f))

        read(id, party).statusCode(200)
            .body("status", equalTo("ACTIVE"))
            .body("currentStrategy.strategyCode", equalTo("CONSERVATIVE"))
            .body("beneficiaries[0].name", equalTo("Jane Doe"))

        assertThat(electionRows(UUID.fromString(id))).containsExactly("CONSERVATIVE")
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `an illegal transition is a 409 and an unknown contract a 404`() {
        val id = create()
        post("$base/$id/suspend").statusCode(409)
        read(UUID.randomUUID().toString(), party).statusCode(404)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a missing header or a provider the pack does not permit is a 400, not a 500`() {
        given().contentType("application/json").header("Idempotency-Key", "k").body(createBody())
            .`when`().post(base).then().statusCode(400)
        given().contentType("application/json").header("X-Customer-Party-Id", party.toString()).body(createBody())
            .`when`().post(base).then().statusCode(400)
        given().contentType("application/json")
            .header("X-Customer-Party-Id", party.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(createBody(providerType = "BANK"))
            .`when`().post(base).then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `another customer's contract is a 404 on read and on every action, and the owner gets 200`() {
        val id = create()
        val stranger = UUID.randomUUID()
        read(id, stranger).statusCode(404)
        post("$base/$id/submit", asParty = stranger).statusCode(404)
        post("$base/$id/incentive-evaluation", """{"contribution":1000,"period":"MONTH"}""", stranger).statusCode(404)
        given().contentType("application/json").header("X-Customer-Party-Id", stranger.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"strategyCode":"DYNAMIC"}""").`when`().put("$base/$id/strategy").then().statusCode(404)
        read(id, party).statusCode(200).body("status", equalTo("DRAFT"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a customer-role caller without the party header is refused`() {
        val id = create()
        read(id, null).statusCode(400)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a retried create with the same Idempotency-Key returns the same contract`() {
        val key = UUID.randomUUID().toString()
        assertThat(create(key)).isEqualTo(create(key))
    }

    private fun electionRows(contractId: UUID): List<String> {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        val sql = "select strategy_code from pension_strategy_elections where contract_id = ? order by id"
        return DriverManager.getConnection(url, "openbank", "openbank_secret").use { conn ->
            conn.prepareStatement(sql).use { st ->
                st.setObject(1, contractId)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
    }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `staff may read any contract without the party header but cannot change one`() {
        val id = seedContract()
        read(id, null).statusCode(200).body("contractId", equalTo(id))
        post("$base/$id/submit", asParty = null).statusCode(400)
    }

    @Test
    @TestSecurity(user = "some-other-service", roles = ["ROLE_API"])
    fun `a party header from a principal that is not the trusted relay is refused`() {
        val id = seedContract()
        read(id, party).statusCode(403)
        post("$base/$id/submit").statusCode(403)
    }

    /**
     * Inserted directly: a staff or untrusted principal cannot create one over HTTP, by design.
     * Ids come from far above anything the app's pooled sequences hand out (the S3 IT idiom): a raw
     * `nextval` here took a value inside the block a running JVM had already allocated, and the
     * app's next insert then collided on the primary key (ADR-0334 S8).
     */
    private fun seedContract(): String {
        val id = UUID.randomUUID()
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        DriverManager.getConnection(url, "openbank", "openbank_secret").use { conn ->
            conn.prepareStatement(
                "insert into pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction, " +
                    "pack_version, provider_entity_id, provider_type, participant_birth_date, status, " +
                    "contribution_amount, contribution_currency, contribution_frequency, created_at, updated_at) " +
                    "values (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 1, ?, " +
                    "'PENSION_COMPANY', date '1985-05-05', 'DRAFT', 1700, 'CZK', 'MONTHLY', now(), now())",
            ).use { st ->
                st.setObject(1, id)
                st.setObject(2, party)
                st.setObject(3, UUID.randomUUID())
                st.executeUpdate()
            }
            conn.prepareStatement(
                "insert into pension_strategy_elections (id, contract_id, strategy_code, effective_from, elected_at) " +
                    "values (1000000000000 + (random() * 1000000000)::bigint, ?, 'BALANCED', current_date, now())",
            ).use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
        }
        return id.toString()
    }
}
