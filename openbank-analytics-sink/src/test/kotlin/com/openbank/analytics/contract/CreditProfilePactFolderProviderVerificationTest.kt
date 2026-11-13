// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.analytics.contract

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.apache.hc.core5.http.HttpRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

@QuarkusTest
@TestProfile(CreditProfilePactTestProfile::class)
@Provider("openbank-analytics-sink")
@PactFolder("../pacts")
class CreditProfilePactFolderProviderVerificationTest {

    @BeforeEach
    fun configureTarget(context: PactVerificationContext) = configureCreditProfilePact(context)

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext, request: HttpRequest) = verifyCreditProfilePact(context, request)
}

@QuarkusTest
@TestProfile(CreditProfilePactTestProfile::class)
class CreditProfilePactAuthorizationTest {
    @Test
    @TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "credit-profile-pact-unrelated-subject"),
            Claim(key = "azp", value = "openbank-unrelated"),
            Claim(key = "preferred_username", value = "service-account-openbank-lending"),
        ],
    )
    fun `a different client cannot read a credit profile`() {
        val path = "/api/v1/analytics/credit-profile/${CreditProfilePactResource.OBSERVED_PARTY}"
        given().get(path).then().statusCode(403)
    }
}
