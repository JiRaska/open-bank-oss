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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side verification for the `POST /api/v1/fraud/score` contract
 * (`SepaPaymentFraudServicePactConsumerTest`, openbank-sepa-payment, ADR-0063 P2 Batch C /
 * ADR-0084 §1). This class was named in that consumer test's own doc comment ("The provider
 * verification lives in FraudPactProviderVerificationTest") but never existed (issue #468) —
 * the consumer contract was committed and generated, but nothing on the provider side replayed it.
 *
 * `@PactBroker` (not `@PactFolder`) — the same fix #1166/#1333 applied to party-service and
 * account-service. `_service-ci.yml` publishes every consumer's pacts to the broker on a main
 * push, but reading the pact from a git-committed file (`@PactFolder`) instead of pulling it back
 * OUT of the broker means Pact-JVM has no broker-side interaction to attach a verification result
 * to — so this class verified the contract correctly but never published a result for ANY
 * fraud-service version, real or otherwise. `can-i-deploy` reads the broker and nothing else, so
 * it permanently saw "no verified pact" for sepa-payment (a money-path consumer) and hard-blocked
 * its deploy — confirmed live: `sepa-payment` #844 (a real bug fix) has sat undeployed since
 * 2026-07-12 with "There is no verified pact between openbank-sepa-payment and openbank-fraud-service"
 * (issue #1348). Rewriting the broker's stale/phantom deployed-version record (a separate bug,
 * also #1348) would NOT have unblocked this — there would still be no verification for whatever
 * the correct version is, until this class actually publishes one.
 *
 * `@EnabledIfSystemProperty` keeps it a no-op locally and on the PR lane, where no broker is
 * configured, matching every other broker-based provider test in the fleet.
 *
 * IMPORTANT: if `SepaPaymentFraudServicePactConsumerTest` changes the contract, regenerate
 * (`./gradlew :openbank-sepa-payment:test --tests "*SepaPaymentFraudServicePactConsumerTest*"`)
 * and commit the updated `pacts/openbank-sepa-payment-openbank-fraud-service.json` in the same PR.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-fraud-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
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
