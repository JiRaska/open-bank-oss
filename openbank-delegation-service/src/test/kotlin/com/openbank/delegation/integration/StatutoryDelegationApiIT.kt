// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.domain.model.StatutoryDelegationRule
import com.openbank.delegation.infrastructure.client.ResilientPartyEligibilityClient
import com.openbank.delegation.infrastructure.client.RestResourceOwnershipClient
import com.openbank.delegation.infrastructure.client.RestStatutoryDelegationRuleReader
import com.openbank.delegation.infrastructure.client.RestStatutoryDelegationSca
import com.openbank.delegation.it.PostgresTestResource
import com.openbank.libs.audit.decision.InputDigest
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@QuarkusTest
@TestProfile(StatutoryDelegationApiIT.Enabled::class)
@QuarkusTestResource(value = PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(
    value = SpendReservationOutboxWriteIT.InMemoryKafkaResource::class,
    restrictToAnnotatedClass = true,
)
class StatutoryDelegationApiIT {
    private val issuer = UUID.randomUUID()
    private val recipient = UUID.randomUUID()
    private val issuerActors = List(2) { UUID.randomUUID() }
    private val recipientActors = List(2) { UUID.randomUUID() }
    private val issuerRule = rule(issuer, issuerActors)
    private val recipientRule = rule(recipient, recipientActors)
    private val rules = mockk<RestStatutoryDelegationRuleReader>()
    private val eligibility = mockk<ResilientPartyEligibilityClient>()
    private val ownership = mockk<RestResourceOwnershipClient>()
    private val sca = mockk<RestStatutoryDelegationSca>()

    class Enabled : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("openbank.delegation.statutory-collection-enabled" to "true")
    }

    @BeforeEach
    fun installPorts() {
        coEvery { rules.current(issuer) } returns issuerRule
        coEvery { rules.current(recipient) } returns recipientRule
        coEvery { eligibility.eligibilityOf(any()) } answers {
            PartyEligibility(firstArg(), active = true, kycLevel = "FULL", partyType = "COMPANY")
        }
        coEvery { ownership.verifyOwnership(any(), any(), any()) } returns OwnershipVerdict.OWNED
        coEvery { sca.consume(any(), any(), any()) } answers { Instant.now().truncatedTo(ChronoUnit.MICROS) }
        QuarkusMock.installMockForType(rules, RestStatutoryDelegationRuleReader::class.java)
        QuarkusMock.installMockForType(eligibility, ResilientPartyEligibilityClient::class.java)
        QuarkusMock.installMockForType(ownership, RestResourceOwnershipClient::class.java)
        QuarkusMock.installMockForType(sca, RestStatutoryDelegationSca::class.java)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "synthetic-edge-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `verified issue and acceptance commit only with both current signatures and a single outbox write`() {
        val resource = UUID.randomUUID()
        val issueKey = UUID.randomUUID()
        val issued = request(issuer, issuerActors.first()).header("Idempotency-Key", issueKey)
            .body(issueBody(resource)).post("$ROOT/issue").then().statusCode(202)
            .header("Cache-Control", "no-store").body("status", equalTo("COLLECTING")).extract()
        val operationId = UUID.fromString(issued.path("id"))
        val grantId = UUID.fromString(issued.path("delegationId"))
        request(issuer, issuerActors.first()).header("Idempotency-Key", issueKey)
            .body(issueBody(resource)).post("$ROOT/issue").then().statusCode(202)
            .body("id", equalTo(operationId.toString()))
        request(issuer, issuerActors.first()).header("Idempotency-Key", issueKey)
            .body(issueBody(UUID.randomUUID())).post("$ROOT/issue").then().statusCode(409)
        request(issuer, UUID.randomUUID()).get("$ROOT/$operationId").then().statusCode(404)

        val firstSignature = UUID.randomUUID()
        sign(issuer, issuerActors.first(), operationId, firstSignature, "COLLECTING")
        sign(issuer, issuerActors.first(), operationId, firstSignature, "COLLECTING")
        assertThat(provenance(operationId, grantId, "DelegationOffered")).isEmpty()
        request(issuer, issuerActors.last()).get(ROOT).then().statusCode(200)
            .body("items[0].id", equalTo(operationId.toString()))

        val lastSignature = UUID.randomUUID()
        val requests = List(2) {
            CompletableFuture.runAsync {
                sign(issuer, issuerActors.last(), operationId, lastSignature, "COMMITTED")
            }
        }
        CompletableFuture.allOf(*requests.toTypedArray()).get(45, TimeUnit.SECONDS)
        val offer = provenance(operationId, grantId, "DelegationOffered").single()
        assertThat(offer).containsExactly("OFFERED", "COMMITTED", operationId.toString(), "true")

        val acceptKey = UUID.randomUUID()
        val acceptOperationId = UUID.fromString(
            request(recipient, recipientActors.first()).header("Idempotency-Key", acceptKey)
                .post("$ROOT/accept/$grantId").then().statusCode(202).extract().path("id"),
        )
        sign(recipient, recipientActors.first(), acceptOperationId, UUID.randomUUID(), "COLLECTING")
        assertThat(provenance(acceptOperationId, grantId, "DelegationActivated")).isEmpty()
        val acceptReceipt = UUID.randomUUID()
        coEvery { rules.current(issuer) } returns issuerRule.copy(attestationId = UUID.randomUUID())
        request(recipient, recipientActors.last()).header("Idempotency-Key", acceptReceipt)
            .body("""{"scaChallengeId":"$acceptReceipt"}""")
            .post("$ROOT/$acceptOperationId/sign").then().statusCode(403)
        assertThat(provenance(acceptOperationId, grantId, "DelegationActivated")).isEmpty()
        coEvery { rules.current(issuer) } returns issuerRule
        sign(recipient, recipientActors.last(), acceptOperationId, acceptReceipt, "COMMITTED")
        val accepted = provenance(acceptOperationId, grantId, "DelegationActivated").single()
        assertThat(accepted).containsExactly("ACTIVE", "COMMITTED", acceptOperationId.toString(), "true")
        request(recipient, recipientActors.first()).header("Idempotency-Key", acceptKey)
            .post("$ROOT/accept/$grantId").then().statusCode(202)
            .body("id", equalTo(acceptOperationId.toString()))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "synthetic-edge-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `statutory mutations reject unbound keys and replay a closed operation`() {
        val actor = issuerActors.first()
        val operation = request(issuer, actor).header("Idempotency-Key", UUID.randomUUID())
            .body(issueBody(UUID.randomUUID())).post("$ROOT/issue").then().statusCode(202).extract().path<String>("id")
        val signature = """{"scaChallengeId":"${UUID.randomUUID()}"}"""
        request(issuer, actor).body(signature).post("$ROOT/$operation/sign").then().statusCode(400)
        request(issuer, actor).header("Idempotency-Key", UUID.randomUUID())
            .body(signature).post("$ROOT/$operation/sign").then().statusCode(400)
        val cancellation = """{"cancel":true}"""
        request(issuer, actor).body(cancellation).post("$ROOT/$operation/close").then().statusCode(400)
        request(issuer, actor).header("Idempotency-Key", UUID.randomUUID())
            .body(cancellation).post("$ROOT/$operation/close").then().statusCode(400)
        repeat(2) {
            request(issuer, actor).header("Idempotency-Key", operation)
                .body(cancellation).post("$ROOT/$operation/close").then().statusCode(200)
                .body("status", equalTo("CANCELLED")).body("signedCount", equalTo(0))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API", "ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "synthetic-shared-subject"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `shared service identity cannot submit a company and actor context`() {
        request(issuer, issuerActors.first()).get(ROOT).then().statusCode(403)
    }

    private fun rule(principal: UUID, actors: List<UUID>) = StatutoryDelegationRule(
        principalPartyId = principal,
        caseId = UUID.randomUUID(),
        attestationId = UUID.randomUUID(),
        evidenceDigest = InputDigest.sha256(mapOf("fixture" to principal.toString())),
        mandatesByRepresentative = actors.associateWith { UUID.randomUUID() },
        validTo = null,
    )

    private fun issueBody(resource: UUID) = """
        {"draft":{"grantorPartyId":"$issuer","granteePartyId":"$recipient","resourceType":"ACCOUNT",
        "resourceId":"$resource","capabilities":["ACCOUNT_READ_BALANCES"]}}
    """.trimIndent()

    private fun request(principal: UUID, actor: UUID): RequestSpecification = given()
        .contentType("application/json")
        .header("X-Customer-Party-Id", principal)
        .header("X-Customer-Actor-Party-Id", actor)

    private fun sign(company: UUID, actor: UUID, operation: UUID, challenge: UUID, state: String) {
        request(company, actor).header("Idempotency-Key", challenge)
            .body("""{"scaChallengeId":"$challenge"}""").post("$ROOT/$operation/sign")
            .then().statusCode(200).body("status", equalTo(state))
    }

    private fun provenance(operation: UUID, grant: UUID, event: String): List<List<String>> {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                """
                SELECT g.status, o.status,
                       CASE o.action WHEN 'ISSUE' THEN g.grantor_statutory_operation_id
                           ELSE g.grantee_statutory_operation_id END AS evidence,
                       (g.xmin = e.xmin AND g.xmin = o.xmin)::text AS atomic
                FROM delegation_grants g
                JOIN delegation_outbox e ON e.aggregate_id = g.id
                JOIN statutory_delegation_operations o ON o.delegation_id = g.id
                WHERE g.id = ? AND o.id = ? AND e.event_type = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, grant)
                statement.setObject(2, operation)
                statement.setString(3, event)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add((1..4).map(rows::getString)) }
                }
            }
        }
    }

    private companion object {
        const val ROOT = "/api/v1/delegations/statutory-operations"
    }
}
