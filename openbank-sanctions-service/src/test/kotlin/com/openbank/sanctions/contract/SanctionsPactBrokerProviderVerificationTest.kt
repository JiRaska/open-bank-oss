// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.sanctions.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-side provider verification for sanctions-service, published-result counterpart to
 * [SanctionsPactProviderVerificationTest].
 *
 * **Why this exists.** sanctions-service had only a `@PactFolder` class, which replays the
 * committed pact from disk and never contacts the broker — so it has **never published a version
 * on main**. `pact-verification-reconcile.yml` reports it on every run:
 *
 * ```
 * NO main VERSION, not dispatching: openbank-sanctions-service has never published a version
 * on main, so its consumers (openbank-fx-service) read as unverified
 * ```
 *
 * and then finishes with "every provider has verified its consumers' latest pacts — nothing to
 * do", which reads as an all-clear over a list of things that are not clear. The consequence:
 * `can-i-deploy` sees no verified pact between fx-service and the sanctions-service running in
 * the environment and blocks every fx-service deploy, permanently. Measured 2026-08-02 on the
 * fx inverse-pair fix (#3189) — merged green, blocked twice, never reached the sandbox.
 *
 * A `@PactFolder` replay is the right PR-lane gate (ADR-0063: zero infra, always runs) but it
 * cannot publish a result, and `can-i-deploy` only reads published results.
 *
 * **Why a second `@Provider("openbank-sanctions-service")` class is safe here.** The collision
 * ADR-0029 D3 warns about is two BROKER-sourced classes both pulling every pact for one provider,
 * or HTTP and MESSAGE targets fighting over the same `@BeforeEach`. This is the sanctioned pair
 * CLAUDE.md documents — `@PactFolder` plus `@PactBroker` gated on `pactbroker.url` — as
 * openbank-ledger-service already carries. sanctions-service has no message-consumer contracts
 * and both classes use [HttpTestTarget] exclusively.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane (`_service-ci.yml` blanks
 * `PACT_BROKER_URL`; the broker has no public ingress, ADR-0056). It runs on main-push with
 * `PUBLISH_RESULTS=true` — that run is what creates the main version the reconciler looks for.
 *
 * **Both of the counterpart's states are handled here**, because the broker serves every
 * consumer's pact for this provider: a missing state fails with MissingStateChangeMethod and
 * publishes a FAILURE, which blocks deploys on a healthy pair.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
@Provider("openbank-sanctions-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class SanctionsPactBrokerProviderVerificationTest {

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

    /** Mirrors [SanctionsPactProviderVerificationTest] — no-op by design, see that class's KDoc. */
    @State("the sanctions lists are seeded and carry no entry for the screened name")
    fun stateNoSeededEntryMatchesTheName() {
        // Intentionally empty — see the KDoc above.
    }

    /** Mirrors [SanctionsPactProviderVerificationTest] — the boot-time seed provides the entries. */
    @State("the sanctions lists are seeded with the boot-time OFAC/EU/UN/PEP entries")
    fun stateSeededSanctionsEntriesExist() {
        // Intentionally empty — see the KDoc above.
    }
}
