// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.integration

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * ADR-0383 D3 — `POST /api/v1/delegations/mcp-client/resolve` over real HTTP against a real database
 * (no mocked repository). OPA is advisory in the test profile, so every refusal asserted here is the
 * handler's own in-code guard; each was also run with that guard removed and went red (PR evidence).
 */
@QuarkusTest
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class McpClientGrantResourceIT {

    private val customer = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val account = UUID.randomUUID()

    private fun body(party: UUID = customer, jkt: String = JKT) =
        """{"grantorPartyId":"$party","clientId":"$CLIENT","keyThumbprint":"$jkt"}"""

    private fun post(body: String?, partyHeader: UUID? = null) = given().contentType(ContentType.JSON)
        .apply { partyHeader?.let { header("X-Customer-Party-Id", it.toString()) } }
        .apply { body?.let { body(it) } }
        .post("/api/v1/delegations/mcp-client/resolve").then()

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-mcp"), Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `mcp-service gets only this customer's active grant, never another customer's grant to the same client`() {
        seed(customer, account, "ACTIVE")
        seed(other, UUID.randomUUID(), "ACTIVE")
        seed(customer, UUID.randomUUID(), "REVOKED")
        val ids = post(body()).statusCode(200).extract().jsonPath().getList<String>("grants.resourceId")
        assertThat(ids).containsExactly(account.toString())
        val keys = post(body()).extract().jsonPath().getMap<String, Any>("grants[0]").keys
        assertThat(keys).containsExactlyInAnyOrder("grantId", "resourceType", "resourceId", "capabilities", "validTo")
        assertThat(post(body(party = other)).extract().jsonPath().getList<String>("grants.resourceId"))
            .doesNotContain(account.toString())
    }

    @Test
    @TestSecurity(user = SERVICES, roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-services"), Claim(key = "preferred_username", value = SERVICES),
        ],
    )
    fun `another service account is refused 403`() {
        seed(customer, account, "ACTIVE")
        post(body()).statusCode(403)
    }

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-mcp"), Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `a request carrying the customer-party header is refused 403`() {
        post(body(), partyHeader = customer).statusCode(403)
    }

    @Test
    @TestSecurity(user = MCP, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "sub",
                value = SUB,
            ), Claim(key = "azp", value = "openbank-mcp"), Claim(key = "preferred_username", value = MCP),
        ],
    )
    fun `a missing body or a malformed thumbprint is 400`() {
        post(null).statusCode(400)
        post(body(jkt = "short")).statusCode(400)
    }

    private fun seed(grantor: UUID, resource: UUID, status: String) = jdbc().use { c ->
        val id = UUID.randomUUID()
        c.prepareStatement(
            """
            insert into delegation_grants
                (id, grantor_party_id, grantee_party_id, grantee_type, grantee_client_id, grantee_key_thumbprint,
                 resource_type, resource_id, approval_policy, valid_from, status, created_at, updated_at,
                 lifecycle_revision)
            values (?, ?, ?, 'MCP_CLIENT', ?, ?, 'ACCOUNT', ?, 'SOLO', now() - interval '1 day', ?, now(), now(), 1)
            """.trimIndent(),
        ).use { s ->
            s.setObject(1, id)
            s.setObject(2, grantor)
            s.setObject(3, DelegationGrant.mcpClientGranteeId(CLIENT))
            s.setString(4, CLIENT)
            s.setString(5, JKT)
            s.setObject(6, resource)
            s.setString(7, status)
            s.executeUpdate()
        }
        c.prepareStatement(
            "insert into delegation_capabilities (grant_id, capability) values (?, 'ACCOUNT_READ_BALANCES')",
        ).use { s ->
            s.setObject(1, id)
            s.executeUpdate()
        }
    }

    private fun jdbc(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val MCP = "service-account-openbank-mcp"
        const val SERVICES = "service-account-openbank-services"
        const val SUB = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"
        const val CLIENT = "mcp-local-reference"
        const val JKT = "0000000000000000000000000000000000000000000"
    }
}
