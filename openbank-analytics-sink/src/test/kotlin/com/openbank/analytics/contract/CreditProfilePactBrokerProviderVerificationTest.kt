// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.analytics.contract

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

@QuarkusTest
@TestProfile(CreditProfilePactTestProfile::class)
@Provider("openbank-analytics-sink")
@PactBroker
@IgnoreNoPactsToVerify
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class CreditProfilePactBrokerProviderVerificationTest {

    @BeforeEach
    fun configureTarget(context: PactVerificationContext) = configureCreditProfilePact(context)

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    @TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "credit-profile-pact-subject"),
            Claim(key = "azp", value = "openbank-lending"),
            Claim(key = "preferred_username", value = "service-account-openbank-lending"),
        ],
    )
    fun verifyPacts(context: PactVerificationContext) = context.verifyInteraction()
}
