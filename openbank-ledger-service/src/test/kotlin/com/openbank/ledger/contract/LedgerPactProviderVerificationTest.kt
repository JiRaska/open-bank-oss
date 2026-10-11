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
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import javax.sql.DataSource

/**
 * Provider-side Pact verification for contracts published by consumers (ADR-0063: Phase 1
 * pilot, `balance-service` → `ledger-service`).
 *
 * Reads the consumer pact from the git-pact folder (`@PactFolder`, resolved relative to this
 * module's working directory at `../pacts` = the monorepo-root `pacts/` dir) and replays each
 * interaction against the running Quarkus test instance to prove the ledger service fulfils the
 * consumer contract. This always runs — no broker, no gate, no CI secret required (ADR-0063
 * chose git-pact over a Pact Broker for exactly this reason: zero new infra dependency).
 *
 * IMPORTANT: if `LedgerTrialBalancePactConsumerTest` (openbank-balance-service) changes the
 * contract, regenerate the pact JSON (`./gradlew :openbank-balance-service:test --tests
 * "*LedgerTrialBalancePactConsumerTest*"`) and commit the updated `pacts/openbank-balance-service-
 * openbank-ledger-service.json` in the same PR, or this test will fail against a stale contract.
 *
 * `@IgnoreNoPactsToVerify(ignoreIoErrors)` makes a missing/unreadable pact file a skip, not a
 * failure — relevant if the folder is ever emptied ahead of a broker migration (see ADR-0063
 * "Migration to a broker is the natural follow-up").
 *
 * Authentication: [TestSecurity] cannot annotate @TestTemplate; instead a request filter is
 * configured below so Pact injects the service role on every replayed request. This matches the
 * production path (balance-service calls ledger with the `service` OIDC role, ADR-0018).
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-ledger-service")
@PactFolder("../pacts")
// Excludes the negative-auth state: a class-level @TestSecurity authenticates every request it
// makes, which would turn the "no valid M2M identity" 401 interactions into 200s. Verified instead
// by LedgerNegativeAuthPactVerificationTest, which carries no @TestSecurity at all (same split as
// TransactionPactFolderProviderVerificationTest / TransactionNegativeAuthPactVerificationTest).
// pact-jvm 4.7.3 matches a filter value against the state name with String.matches (a full-match
// Java regex), so the negative lookahead holds.
@PactFilter("^(?!" + NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class LedgerPactProviderVerificationTest {

    @Inject
    lateinit var dataSource: DataSource

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        // Add the service role header so Pact requests pass the OIDC role check.
        // In dev-mode Quarkus maps "TestSecurity" via a dev-only OIDC provider;
        // here we rely on the Quarkus test security extension being active.
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * State: "ledger has journal entries for the reporting date"
     * The Quarkus test instance uses the same Testcontainer DB as LedgerApiIT.
     * If the IT suite runs first (same JVM forked process), the DB may already have data.
     * If running in isolation the DB is empty → trial-balance returns an empty balanced result,
     * which still satisfies the contract shape (type matchers, not value matchers).
     */
    @State("ledger has cumulative frozen monthly evidence for the reporting date")
    fun stateWithFrozenMonthlyTrialBalance() = FinrepFrozenMonthPactSeed.seed(dataSource)

    // Broker retains the former FINREP consumer version until its deployment window expires.
    @State("ledger has frozen monthly trial balance for the reporting date")
    fun stateWithLegacyFrozenMonth() = FinrepLegacyMonthPactSeed.seed(dataSource)

    /**
     * Backward-compatible state for balance-service's live `/journals/trial-balance` pact.
     * That contract uses type matchers and accepts the fresh test DB's balanced zero-line result;
     * FINREP's distinct state above deliberately seeds immutable closed-period evidence.
     */
    @State("ledger has journal entries for the reporting date")
    fun stateWithJournalEntriesForBalancePact() = Unit

    @State("ledger has no journal entries")
    fun stateWithNoJournalEntries() {
        // No setup needed — a fresh Testcontainer DB has no journals by default.
    }

    @State("the standard chart of accounts is seeded")
    fun stateWithSeededChartOfAccounts() {
        // No setup needed — the V3/V5 Flyway migrations seed the standard chart (1100 cash-clearing,
        // 2100 deposit-control, …) with stable UUIDs into the fresh Testcontainer DB, so the
        // transaction-service postJournal contract replays against real, enabled, leaf GL accounts.
    }

    /**
     * treasury-service's nostro-reconciliation reads (ADR-0315 D5, #10896), seeded by
     * [NostroPactSeed]: a balanced two-line journal on [NostroPactSeed.STATEMENT_DATE], Dr 1500 (MM placement, CZK) / Cr 1001 (CZK nostro, the fixed id
     * V29__treasury_money_market_accounts.sql seeds) — the same shape treasury's own SETTLED
     * posting for an MM_PLACEMENT deal produces (`Postings.kt`). Inserted directly against
     * `journal_entries`/`journal_lines` (V1's schema: `side` is a single 'D'/'C' char, the table is
     * partitioned by `entry_date`), the same direct-JDBC seeding
     * [stateWithFrozenMonthlyTrialBalance] above already uses for this Testcontainer DB — a
     * `LedgerUseCase.postJournal` call needs a Vert.x context this bare `@BeforeEach`/`@State`
     * thread does not carry (`CLAUDE.md`'s reactive-repo-off-thread note).
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
