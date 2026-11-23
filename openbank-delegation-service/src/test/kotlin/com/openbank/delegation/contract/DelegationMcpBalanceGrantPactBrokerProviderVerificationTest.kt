// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.delegation.integration.SpendReservationOutboxWriteIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@QuarkusTest
@TestProfile(McpBalanceGrantProviderFixture.ConnectionBinding::class)
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
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DelegationMcpBalanceGrantPactBrokerProviderVerificationTest : McpBalanceGrantProviderFixture() {
    @BeforeEach
    fun replayAnonymousWhenRequired(context: PactVerificationContext?) = configureTarget(context, anonymousStatus = 401)

    @State("an active MCP balance grant matches the requested binding")
    fun activeGrant() = seedActiveGrant()
}
