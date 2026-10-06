// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.analytics.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.analytics.infrastructure.clickhouse.ClickHouseClient
import com.openbank.analytics.it.AnalyticsPactClickHouseResource
import com.openbank.analytics.it.RedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/** Main-push broker verification publishes results for can-i-deploy; folder twins run before merge. */
@QuarkusTest
@QuarkusTestResource(RedpandaTestResource::class)
@QuarkusTestResource(AnalyticsPactClickHouseResource::class)
@TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_API"])
@Provider("openbank-analytics-sink")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class AnalyticsCreditProfilePactBrokerProviderVerificationTest {
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject lateinit var clickHouse: ClickHouseClient

    @Inject lateinit var testIdentityAssociation: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context != null &&
            context.interaction.providerStates.any {
                it.name == CreditProfilePactState.NEGATIVE_AUTH_STATE
            }
        ) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(CreditProfilePactState.PROFILE_STATE)
    fun observedProfileExists() {
        CreditProfilePactState.seed(clickHouse)
    }

    @State(CreditProfilePactState.NEGATIVE_AUTH_STATE)
    fun noIdentity() = Unit
}
