// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.risk.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.risk.integration.FakeLedgerPort
import com.openbank.risk.integration.RiskSnapshotApiIT
import com.openbank.risk.it.PostgresTestResource
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

/**
 * The broker half of the risk engine's provider pair (ADR-0313 D6): the same replay as
 * [RiskEnginePactProviderVerificationTest], sourced from the Pact Broker and gated on
 * `pactbroker.url`, so it runs on main-push only and PUBLISHES the verification result
 * `can-i-deploy` reads. Both classes use [CapitalPactState].
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-finrep", roles = ["ROLE_API"])
@Provider("openbank-risk-engine")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class RiskEnginePactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // The class-level @TestSecurity would authenticate the negative interaction and turn its
        // expected 401 into a 200 mismatch, or hide a provider that stopped enforcing authn.
        if (context?.interaction?.providerStates?.any { it.name == CapitalPactState.NO_IDENTITY } == true) {
            testIdentity.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(CapitalPactState.NO_IDENTITY)
    fun noIdentity() {
        // Intentionally empty: the state IS the absence of an identity (see verifyPacts).
    }

    @State(CapitalPactState.NAME)
    fun tiedOutRun(): Map<String, Any> = CapitalPactState.seed(ledger)

    /** C 72.00's liquidity interaction reads the same seeded run (the same book and report date). */
    @State(CapitalPactState.LIQUIDITY_NAME)
    fun tiedOutRunWithLiquidity(): Map<String, Any> = CapitalPactState.seed(ledger)
}
