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
import org.apache.hc.core5.http.HttpRequest
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

    /** Replays the unrelated workload's 403 through the same OIDC boundary as the allowed calls. */
    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext, request: HttpRequest) = verifyCreditProfilePact(context, request)
}
