// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.contract

import au.com.dius.pact.core.model.SynchronousRequestResponse
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
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
import javax.sql.DataSource

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
@QuarkusTestResource(
    value = com.openbank.ledger.it.PostgresRedpandaTestResource::class,
    restrictToAnnotatedClass = true,
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-ledger-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class LedgerPactBrokerProviderVerificationTest {

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @Inject
    lateinit var dataSource: DataSource

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    private var reportingMonth = FinrepFrozenMonthPactSeed.REPORTING_MONTH
    private var yearToDate = true

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context != null &&
            context.interaction.providerStates.any {
                it.name ==
                    "ledger has frozen monthly trial balance for the reporting date"
            }
        ) {
            val paths = context.pact.interactions
                .filter {
                    it.providerStates.any { state ->
                        state.name ==
                            "ledger has frozen monthly trial balance for the reporting date"
                    }
                }
                .map { (it as SynchronousRequestResponse).request.path }
            val plan = FinrepFrozenMonthPactSeed.requestPlan(paths)
            reportingMonth = plan.first
            yearToDate = plan.second
        }
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // The missing-identity interaction expects 401. Class-level @TestSecurity otherwise
        // authenticates every broker replay, including this one, and would produce a 200.
        if (context != null && context.interaction.providerStates.any { it.name == NEGATIVE_AUTH_STATE }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }

    @State("ledger has frozen monthly trial balance for the reporting date")
    fun stateWithFrozenMonthlyTrialBalance() =
        FinrepFrozenMonthPactSeed.seed(dataSource, reportingMonth, yearToDate, resetFixture = true)

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

    /**
     * Seeds exactly what [LedgerPactProviderVerificationTest.stateWithNostroJournalLine] does (shared [NostroPactSeed])
     * (treasury-service's nostro-reconciliation reads, ADR-0315 D5, #10896) — the broker serves
     * every consumer's pact for this provider, so a state this class lacks fails verification with
     * MissingStateChangeMethod and blocks treasury-service deploys on can-i-deploy, the same
     * failure mode documented above for balance-service's trial-balance pact.
     */
    @State("ledger has a nostro journal line on 1001 for the statement date")
    fun stateWithNostroJournalLine() = NostroPactSeed.seedCzkNostroLine(dataSource)

    /** treasury's native-currency balance read (#11107): EUR 10,000.00 Dr on 1002, base CZK differs. */
    @State("ledger has a EUR journal line on 1002 for the statement date")
    fun stateWithEurNostroJournalLine() = NostroPactSeed.seedEurNostroLine(dataSource)

    /**
     * treasury's unknown-account read (#11113): no setup — 9999 is in no chart migration, so the
     * ledger's own GlAccountNotFoundExceptionMapper answers, and treasury reads exactly that body as
     * "not held" (any other 404 is an upstream failure there).
     */
    @State("ledger does not hold GL account 9999")
    fun stateWithUnknownGlAccount() = Unit
}
