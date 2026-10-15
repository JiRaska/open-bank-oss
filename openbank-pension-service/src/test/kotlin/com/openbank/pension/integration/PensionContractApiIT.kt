// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ProviderFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
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
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val RELAY = "service-account-openbank-edge"
private const val RELAY_SUB = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"

/**
 * Drives the real HTTP routes against a real Postgres. Only this shape proves the routes are
 * REGISTERED (#3371), that the entity columns exist (entity-column-names), and that the strategy
 * history is appended rather than overwritten — read back here with plain JDBC.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PensionContractApiIT {

    private val base = "/api/v2/pension/contracts"
    private val party: UUID = UUID.randomUUID()

    private fun createBody(providerType: String = "PENSION_COMPANY") = """
        {
          "productLine": "DPS",
          "jurisdiction": "CZ",
          "providerEntityId": "${ProviderFixtures.ID}",
          "providerType": "$providerType",
          "birthDate": "1985-05-05",
          "residencyCountry": "CZ",
          "schedule": { "amount": 1700, "currency": "CZK", "frequency": "MONTHLY" },
          "strategyCode": "CONSERVATIVE",
          "beneficiaries": [ { "name": "Jane Doe", "sharePercent": 100 } ]
        }
    """.trimIndent()

    /** Fixture: the S1 create route is retired (#12435); the use case still opens a draft. */
    private fun create(key: String = UUID.randomUUID().toString()): String = onVertx {
        useCase.createDraft(
            CreateDraftCommand(
                participantPartyId = party, productLine = ProductLine.DPS, jurisdiction = "CZ",
                providerEntityId = ProviderFixtures.ID, providerType = ProviderType.PENSION_COMPANY,
                birthDate = LocalDate.parse("1985-05-05"), residencyCountry = "CZ", residencyEvidence = emptySet(),
                hasGuardian = false,
                schedule = ContributionSchedule(BigDecimal("1700"), "CZK", ContributionFrequency.MONTHLY),
                initialStrategy = "CONSERVATIVE",
                beneficiaries = listOf(Beneficiary("Jane Doe", null, BigDecimal("100"))),
                idempotencyKey = key,
            ),
        ).id.toString()
    }

    private fun submitByFixture(id: String) {
        onVertx { useCase.submit(Caller.customer(party), UUID.fromString(id)) }
    }

    @Inject
    lateinit var useCase: PensionContractUseCase

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

    @Inject
    lateinit var references: com.openbank.pension.application.port.out.ContractReferenceRepository

    private fun <T> onVertx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    /** Fixture: the product activates only through onboarding (S8), which this class does not drive. */
    private fun activateDirectly(id: String) = onVertx {
        val pending = requireNotNull(contracts.findById(UUID.fromString(id)))
        contracts.save(pending.activate(LocalDate.now(), Instant.now()))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `the retired S1 draft routes are not served`() {
        // #12435: a contract is born through onboarding only. The create method is gone from the
        // collection (405, the collection still serves GET) and submit is not a route at all (404).
        post(base, createBody()).statusCode(405)
        val id = create()
        post("$base/$id/submit").statusCode(404)
        read(id, party).statusCode(200).body("status", equalTo("DRAFT"))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `the participant annual-statement read is served at its published path`() {
        val id = create()
        // Not issued yet: the RESOURCE's 404 (its message), which an unregistered route (#3371)
        // cannot produce — a bare status would read the same either way.
        val body = given().header("X-Customer-Party-Id", party.toString())
            .`when`().get("$base/$id/annual-statements/2025").then().statusCode(404)
            .extract().asString()
        assertThat(body).contains("no annual statement for 2025")
        given().header("X-Customer-Party-Id", UUID.randomUUID().toString())
            .`when`().get("$base/$id/annual-statements/2025").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `the participant cannot activate or terminate through the retired S1 routes`() {
        val id = create()
        submitByFixture(id)
        // ADR-0334 S8: activation is the onboarding workflow's (KID, SCA, cooling-off); termination
        // is S5's quote/sign flow. Neither S1 shortcut is served any more.
        post("$base/$id/activate").statusCode(404)
        post("$base/$id/early-termination", """{"currentValue":5000,"confirm":true}""").statusCode(404)
        read(id, party).statusCode(200).body("status", equalTo("PENDING_ACTIVATION"))
    }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `staff free-text search finds a contract by birth date, id fragment, product and reference`() {
        val id = create()
        val reference = onVertx { references.referenceFor(UUID.fromString(id)) }
        // Bare digits of the birth date (rodné číslo style) and the ISO month both match.
        given().queryParam("q", "850505").`when`().get(base).then().statusCode(200)
            .body("contractId", hasItem(id))
            .body("participantBirthDate", hasItem("1985-05-05"))
        given().queryParam("q", "1985-05").`when`().get(base).then().statusCode(200)
            .body("contractId", hasItem(id))
        // A fragment of the contract id, the product line, and the payment reference (VS).
        given().queryParam("q", id.take(8)).`when`().get(base).then().statusCode(200)
            .body("contractId", hasItem(id))
        val byProduct = given().queryParam("q", "dps").`when`().get(base)
        assertThat(byProduct.statusCode).`as`(byProduct.body.asString()).isEqualTo(200)
        byProduct.then().body("contractId", hasItem(id))
        // (the reference query doubles as the diagnostic: any non-200 prints the service's body)
        val byReference = given().queryParam("q", reference).`when`().get(base)
        assertThat(byReference.statusCode).`as`(byReference.body.asString()).isEqualTo(200)
        byReference.then().body("contractId", hasItem(id))
        // The status filter narrows the search, never widens it.
        given().queryParam("q", "850505").queryParam("status", "CLOSED").`when`().get(base).then().statusCode(200)
            .body("contractId", not(hasItem(id)))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
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
    @TestSecurity(user = RELAY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = RELAY_SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = RELAY),
        ],
    )
    fun `the full participant lifecycle runs over real HTTP`() {
        val id = create()
        submitByFixture(id)
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
    @TestSecurity(user = RELAY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = RELAY_SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = RELAY),
        ],
    )
    fun `an illegal transition is a 409 and an unknown contract a 404`() {
        val id = create()
        post("$base/$id/suspend").statusCode(409)
        read(UUID.randomUUID().toString(), party).statusCode(404)
    }

    @Test
    @TestSecurity(user = RELAY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = RELAY_SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = RELAY),
        ],
    )
    fun `another customer's contract is a 404 on read and on every action, and the owner gets 200`() {
        val id = create()
        val stranger = UUID.randomUUID()
        read(id, stranger).statusCode(404)
        post("$base/$id/suspend", asParty = stranger).statusCode(404)
        post("$base/$id/incentive-evaluation", """{"contribution":1000,"period":"MONTH"}""", stranger).statusCode(404)
        given().contentType("application/json").header("X-Customer-Party-Id", stranger.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"strategyCode":"DYNAMIC"}""").`when`().put("$base/$id/strategy").then().statusCode(404)
        read(id, party).statusCode(200).body("status", equalTo("DRAFT"))
    }

    @Test
    @TestSecurity(user = RELAY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = RELAY_SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = RELAY),
        ],
    )
    fun `a customer-role caller without the party header is refused`() {
        val id = create()
        read(id, null).statusCode(400)
    }

    @Test
    @TestSecurity(user = RELAY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = RELAY_SUB,
            ), Claim(key = "azp", value = "openbank-edge"), Claim(key = "preferred_username", value = RELAY),
        ],
    )
    fun `a retried draft with the same Idempotency-Key returns the same contract`() {
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
        post("$base/$id/suspend", asParty = null).statusCode(400)
    }

    @Test
    @TestSecurity(user = RELAY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = RELAY_SUB,
            ), Claim(key = "azp", value = "openbank-admin-ui"), Claim(key = "preferred_username", value = RELAY),
        ],
    )
    fun `the relay's username on a token issued to another client is refused`() {
        val id = seedContract()
        read(id, party).statusCode(403)
        post("$base/$id/suspend").statusCode(403)
    }

    @Test
    @TestSecurity(user = "some-other-service", roles = ["ROLE_API"])
    fun `a party header from a principal that is not the trusted relay is refused`() {
        val id = seedContract()
        read(id, party).statusCode(403)
        post("$base/$id/suspend").statusCode(403)
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
