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
 * Git-pact provider verification for fraud-service — the half that actually runs before a merge
 * (issue #2327, gated by `check-pact-provider-replay.py` per #2338).
 *
 * fraud-service is the provider for one committed pact: sepa-payment's `POST /api/v1/fraud/score`,
 * the synchronous scoring call on the SEPA payment path (ADR-0084 §1). Its only verification class
 * was [FraudPactProviderVerificationTest] — `@PactBroker`-sourced and
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated. On a pull request that property is empty
 * (`_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks `PACT_BROKER_URL` off
 * main-push, because the broker has no public ingress, ADR-0056), so it skipped and the contract
 * was replayed only AFTER the merge.
 *
 * That matters more here than the count suggests. A consumer pact cannot catch a wrong request
 * path — the Pact mock server answers whatever path the client asks for, so only the provider
 * replay goes red (#2269) — and this is the call a payment makes before deciding whether to
 * proceed. One pact, money path.
 *
 * ## Additive, not a replacement
 *
 * The broker twin stays as it is: its published verification result is the only thing ADR-0092's
 * `can-i-deploy` reads, and the same swap elsewhere in the fleet blocked deploys for days
 * (party-service #371/#1166, account-service #372/#1166). Git source for the PR lane, broker
 * source for the published result — the pair openbank-ledger-service carries. Two `@Provider`
 * classes collide only when BOTH pull from the broker, since each then fetches every pact it holds.
 *
 * ## Upkeep
 *
 * A deliberate duplicate of the broker twin's body: same `@State` handler, same target wiring. A
 * change to one belongs in the other, or the same contract passes from git and fails from the
 * broker (or the reverse).
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-fraud-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class FraudPactFolderProviderVerificationTest {

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
