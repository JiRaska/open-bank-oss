// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.delegation.integration.SpendReservationOutboxWriteIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/** Replays the committed MCP HTTP pacts against the Delegation HTTP resources. */
@QuarkusTest
@TestProfile(McpClientGrantProviderFixture.ResolverDouble::class)
@QuarkusTestResource(SpendReservationOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-mcp", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "6a8f2c1e-0b4d-4e7a-9c3f-1d2e3f4a5b6c"),
        Claim(key = "azp", value = "openbank-mcp"),
        Claim(key = "preferred_username", value = "service-account-openbank-mcp"),
    ],
)
@Provider("openbank-delegation-service")
@Consumer("openbank-mcp-service")
@PactFolder("../pacts")
class DelegationMcpClientGrantPactFolderProviderVerificationTest : McpClientGrantProviderFixture() {
    @BeforeEach
    fun replayAnonymousWhenRequired(context: PactVerificationContext?) = configureTarget(context)

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("an active MCP_CLIENT grant exists for the pact party, client and key")
    fun activeGrant() = seedActiveGrant()

    @State("no MCP_CLIENT grant exists for the pact party, client and key")
    fun noGrant() = seedNoGrant()

    @State("an active MCP_CLIENT propose grant exists for the pact party, client, key and account")
    fun activeProposeGrant() = seedActiveProposeGrant()

    @State("no MCP_CLIENT propose grant exists for the pact party, client, key and account")
    fun noProposeGrant() = seedNoProposeGrant()
}
