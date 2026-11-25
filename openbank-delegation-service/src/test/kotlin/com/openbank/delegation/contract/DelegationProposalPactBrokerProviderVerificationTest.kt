// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.delegation.integration.PaymentProposalApiIT
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/** Verifies the Customer Edge payment-proposal Pact using the HTTP and PostgreSQL fixture. */
@QuarkusTest
@QuarkusTestResource(PaymentProposalApiIT.InMemoryKafka::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = DelegationProposalProviderFixture.EDGE, roles = ["ROLE_OPERATOR"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = DelegationProposalProviderFixture.EDGE),
    ],
)
@Provider("openbank-delegation-service")
@Consumer("openbank-customer-edge")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "false")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DelegationProposalPactBrokerProviderVerificationTest : DelegationProposalProviderFixture() {

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("a PENDING payment proposal exists for the customer")
    fun pendingProposal() = seed("PENDING")

    @State("an APPROVED payment proposal exists for the customer")
    fun approvedProposal() = seed("APPROVED")
}
