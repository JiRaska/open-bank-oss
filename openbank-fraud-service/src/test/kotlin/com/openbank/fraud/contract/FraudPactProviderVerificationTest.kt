// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side verification for the `POST /api/v1/fraud/score` contract
 * (`SepaPaymentFraudServicePactConsumerTest`, openbank-sepa-payment, ADR-0063 P2 Batch C /
 * ADR-0084 §1). This class was named in that consumer test's own doc comment ("The provider
 * verification lives in FraudPactProviderVerificationTest") but never existed (issue #468) —
 * the consumer contract was committed and generated, but nothing on the provider side replayed it.
 *
 * git-pact (`@PactFolder`), mirroring `LedgerPactProviderVerificationTest` /
 * `AccountEventPactProviderVerificationTest`: always runs, no broker/CI secret required.
 *
 * IMPORTANT: if `SepaPaymentFraudServicePactConsumerTest` changes the contract, regenerate
 * (`./gradlew :openbank-sepa-payment:test --tests "*SepaPaymentFraudServicePactConsumerTest*"`)
 * and commit the updated `pacts/openbank-sepa-payment-openbank-fraud-service.json` in the same PR.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_SERVICE", "ROLE_OPERATOR"])
@Provider("openbank-fraud-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class FraudPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("the fraud scoring engine is available")
    fun stateScoringEngineAvailable() {
        // No setup needed: the stub rule set (ADR-0084 §1 Phase 1) always returns ALLOW,
        // which satisfies the contract's type matchers on verdict/score.
    }
}
