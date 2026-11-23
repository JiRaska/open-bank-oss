// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.integration.SpendReservationOutboxWriteIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-balance-mcp-reader", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "b4b4b4b4-0000-4000-8000-000000000004"),
        Claim(key = "azp", value = "openbank-balance-mcp-reader"),
        Claim(key = "preferred_username", value = "service-account-openbank-balance-mcp-reader"),
    ],
)
@Provider("openbank-delegation-service")
@Consumer("openbank-balance-service")
@PactFolder("../pacts")
class DelegationMcpBalanceGrantPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("an active MCP balance grant matches the requested binding")
    fun activeGrant() {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                    (id, grantor_party_id, grantee_party_id, grantee_type, grantee_client_id, grantee_key_thumbprint,
                     resource_type, resource_id, valid_from, valid_to, status)
                VALUES (?, ?, ?, 'MCP_CLIENT', ?, ?, 'ACCOUNT', ?, now() - interval '1 day',
                        now() + interval '1 day', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, GRANT)
                statement.setObject(2, PARTY)
                statement.setObject(3, DelegationGrant.mcpClientGranteeId(CLIENT))
                statement.setString(4, CLIENT)
                statement.setString(5, "0".repeat(43))
                statement.setObject(6, ACCOUNT)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, 'ACCOUNT_READ_BALANCES') " +
                    "ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setObject(1, GRANT)
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        val GRANT: UUID = UUID.fromString("b4b4b4b4-0000-4000-8000-000000000001")
        val PARTY: UUID = UUID.fromString("b4b4b4b4-0000-4000-8000-000000000002")
        val ACCOUNT: UUID = UUID.fromString("b4b4b4b4-0000-4000-8000-000000000003")
        const val CLIENT = "mcp-local-reference"
    }
}
