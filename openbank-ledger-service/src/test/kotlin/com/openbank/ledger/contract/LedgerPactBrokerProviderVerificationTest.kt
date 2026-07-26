// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.contract

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
 * Broker-side provider verification for ledger-service, published-result counterpart to
 * [LedgerPactProviderVerificationTest] (issue #1009).
 *
 * `_service-ci.yml`'s "Publish consumer pacts to broker" step runs unconditionally for every
 * consumer service on a main push, including billing-service's `postJournal` contract
 * (`BillingLedgerPostJournalPactConsumerTest`). But ledger-service's only provider verification
 * was git-pact (`@PactFolder`, ADR-0063 pilot for balance-service) — nothing ever pulled
 * billing-service's pact BACK OUT of the broker to verify it and publish a result, so
 * `can-i-deploy` permanently saw "no verified pact" for billing-service <-> ledger-service and
 * blocked every ledger-service deploy touching that pair (confirmed live: #945 merged clean,
 * built green, but sat undeployed on this gate).
 *
 * A second `@Provider("openbank-ledger-service")` class is safe here (unlike the collision
 * CLAUDE.md warns about): that footgun is HTTP vs MESSAGE target dispatch fighting over the same
 * `@BeforeEach`; ledger-service has no message-consumer contracts, both classes here use
 * [HttpTestTarget] exclusively, so verifying the same interaction from two pact sources is at
 * worst redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on PR-lane CI (no broker configured there,
 * matching every other broker-based provider test in the fleet) — the git-pact class keeps
 * running unconditionally regardless, so balance-service coverage (ADR-0063's whole point:
 * zero-infra-dependency verification) is unaffected by this addition.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-ledger-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class LedgerPactBrokerProviderVerificationTest {

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

    /**
     * Same state as [LedgerPactProviderVerificationTest.stateWithSeededChartOfAccounts] — no
     * setup needed, the V3/V5 Flyway migrations seed the standard chart into the fresh
     * Testcontainer DB (billing-service's postJournal contract posts against real, enabled leaf
     * GL accounts a0000000-...-002 and a0000000-...-004003).
     */
    @State("the standard chart of accounts is seeded")
    fun stateWithSeededChartOfAccounts() {
        // No-op — see docstring.
    }

    /**
     * The broker serves EVERY consumer's pact for this provider, not just billing-service's, so
     * this class must handle every state its git-pact counterpart does: a state this class lacks
     * fails verification with MissingStateChangeMethod, the result publishes as a failure, and
     * `can-i-deploy` then blocks ledger-service deploys on a pair that is otherwise healthy —
     * which is exactly what happened to balance-service's two trial-balance interactions and kept
     * the #945 reversal fix out of the sandbox.
     *
     * Bodies mirror [LedgerPactProviderVerificationTest] verbatim (no-op by design): the pact uses
     * type matchers, so any valid trial-balance response satisfies the contract shape, and seeding
     * real double-entry data here would couple the provider test to the internal posting API — the
     * anti-pattern Pact exists to avoid. LedgerApiIT covers the seeded-data path.
     */
    @State("ledger has journal entries for the reporting date")
    fun stateWithJournalEntries() {
        // No-op — see docstring.
    }

    @State("ledger has no journal entries")
    fun stateWithNoJournalEntries() {
        // No setup needed — a fresh Testcontainer DB has no journals by default.
    }
}
