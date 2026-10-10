// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.integration.AuditAuthzWiringIT
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-sourced twin of [AuditPactProviderVerificationTest] (main-push only, gated on
 * `pactbroker.url`), so a verification result and provider version reach the broker. Replays the
 * 401 interactions too, clearing the test identity for them.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(AuditAuthzWiringIT.EnforcedProfile::class)
@TestSecurity(user = "pact-credit-risk-person", roles = ["ROLE_CREDIT_RISK"])
@Provider("openbank-audit-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class AuditPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @Inject
    lateinit var repository: AuditRepository

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context != null && context.interaction.providerStates.any { it.name == NEGATIVE_AUTH_STATE }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoCallerIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }

    @State(EVIDENCE_STATE)
    fun evidenceExists() = AuditPactStates.seedEvidence(repository)
}
