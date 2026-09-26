// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
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
 * Broker-side provider verification for aml-service, published-result counterpart to
 * [AmlPactProviderVerificationTest].
 *
 * **Why this exists.** aml-service had only a `@PactFolder` class, which replays the committed
 * pact from disk and never contacts the broker — so aml-service has **never published a version
 * on main**. `pact-verification-reconcile.yml` says so in as many words on every run:
 *
 * ```
 * NO main VERSION, not dispatching: openbank-aml-service has never published a version on
 * main, so its consumers (openbank-fx-service) read as unverified
 * ```
 *
 * and then finishes with "every provider has verified its consumers' latest pacts — nothing to
 * do". The consequence is not a delay that clears itself: `can-i-deploy` sees no verified pact
 * between fx-service and the aml-service running in the environment, and **blocks every
 * fx-service deploy, permanently**. Measured on 2026-08-02: the fx inverse-pair fix (#3189)
 * merged green and could not reach the sandbox at all, on two separate auto-deploy runs.
 *
 * A `@PactFolder` replay is the right PR-lane gate (ADR-0063: zero infra, always runs) but it
 * cannot publish a result, and `can-i-deploy` only reads published results. The two classes
 * answer different questions and the service needs both.
 *
 * **Why a second `@Provider("openbank-aml-service")` class is safe here.** The footgun
 * `rules.yaml`/ADR-0029 D3 warns about is two BROKER-sourced classes both pulling every pact for
 * one provider, or HTTP and MESSAGE targets fighting over the same `@BeforeEach`. This is the
 * sanctioned pair CLAUDE.md documents — a `@PactFolder` class plus a `@PactBroker` class gated on
 * `pactbroker.url` — exactly as openbank-ledger-service already carries. aml-service has no
 * message-consumer contracts and both classes use [HttpTestTarget] exclusively, so verifying the
 * same interaction from two sources is at worst redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, where `_service-ci.yml` blanks
 * `PACT_BROKER_URL` (the broker has no public ingress, ADR-0056). It runs on main-push, where
 * `PUBLISH_RESULTS=true`, which is the whole point — that run is what creates the main version
 * the reconciler is looking for.
 *
 * **Every state its git-pact counterpart handles must be handled here too.** The broker serves
 * EVERY consumer's pact for this provider, not just the one in `pacts/`; a missing state fails
 * with MissingStateChangeMethod, publishes as a FAILURE, and then blocks deploys on a pair that
 * is otherwise healthy — turning this fix into the very problem it removes.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_aml_it")],
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR", "ROLE_COMPLIANCE"])
@Provider("openbank-aml-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class AmlPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    /**
     * The class-level `@TestSecurity` authenticates every request, which would turn a consumer's
     * 401/403 interaction green-by-accident into a mismatch — or, worse, hide a provider that
     * stopped enforcing authz. For [NO_IDENTITY_STATE] the test identity is cleared in
     * `verifyPacts`, so the request arrives anonymous, exactly as the consumer encoded it.
     */
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
        if (context?.interaction?.providerStates?.any { it.name == NO_IDENTITY_STATE } == true) {
            testIdentity.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    /**
     * Mirrors [AmlPactProviderVerificationTest.stateNoExistingCaseForIdempotencyKey] verbatim, and
     * is intentionally a no-op for the same reason: a fresh Testcontainer Postgres + Valkey carry
     * neither the case row nor the Redis idempotency entry, so `AmlCaseService.createCase` takes
     * the create branch and answers 201.
     *
     * Seeding the other direction from here is not merely unnecessary but unsafe — both stores are
     * reached through `suspend` reactive APIs that cannot be driven from a plain JUnit state-change
     * callback without a Vert.x context (the HR000068 trap).
     */
    @State("no AML case exists for the FX conversion idempotency key")
    fun stateNoExistingCaseForIdempotencyKey() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * The negative-auth case (ADR-0279 #3): a request with no identity must be refused, not
     * silently authorised. No committed consumer pact currently exercises this provider state —
     * the handler exists so the boundary is pinned into the contract per the fleet-wide convention
     * ([com.openbank.lending.contract.LoanBookPactState.NO_IDENTITY]) and so a future consumer
     * interaction encoding it verifies immediately without a provider-side change.
     */
    @State(NO_IDENTITY_STATE)
    fun noIdentity() {
        // Intentionally empty: the state IS the absence of an identity (see verifyPacts).
    }

    private companion object {
        const val NO_IDENTITY_STATE = "no valid identity is presented"
    }
}
