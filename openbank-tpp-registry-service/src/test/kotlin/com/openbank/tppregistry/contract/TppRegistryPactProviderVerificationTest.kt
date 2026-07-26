// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side Pact verification for tpp-registry (issue #2255 dimension C3). Replays
 * psd2-service's `TppRegistryCheckPactConsumerTest` contract for the eIDAS licence gate
 * (`GET /api/v1/tpp-registry/check`) against a real booted instance.
 *
 * Git-pact (`@PactFolder`, ADR-0063): reads the consumer pacts from the monorepo-root `pacts/` dir
 * (resolved relative to this module's working directory) and **always runs** — no broker, no CI
 * secret, no gate. That matters more than usual here: a committed pact nobody replays is worthless
 * against the likeliest defect, a request path the provider does not serve, and that is exactly how
 * finrep shipped a call to a ledger route that had never existed while its unit tests passed
 * against a mocked port (#2269).
 *
 * **This must remain the ONLY `@Provider("openbank-tpp-registry-service")` class in the repo.** Two
 * classes with the same provider name each pull every pact naming that provider and fight over the
 * same `@BeforeEach` target, so they collide.
 *
 * The state seeds nothing, and that is a property of the fixture rather than a shortcut: nothing
 * registers the unknown TPP id the refusal interaction uses, so a fresh Testcontainer DB satisfies
 * it by construction.
 *
 * The allow branch (issue #2340) is now in the committed pact: it was withheld until
 * `TppRepositoryImpl.toDomain()` stopped mapping the entity's `BIGSERIAL` id through
 * `UUID.fromString(id.toString())`, which threw on any registered row (400
 * `"Invalid UUID string: 2"`, found by this very replay while psd2's consumer test stayed green).
 * `V1__init.sql` already seeds `CZ-CNB-TEST-AISP` ACTIVE/AISP with no QWAC expiry, so the state
 * handler below needs no setup either.
 *
 * `@TestSecurity` matches `checkAuthorization`'s `@RolesAllowed("ROLE_API", "ROLE_OPERATOR",
 * "ROLE_ADMIN")` — psd2 calls it with an M2M client-credentials token in production (ADR-0018).
 * `@TestSecurity` cannot annotate a `@TestTemplate`, hence the class-level annotation. The lone
 * `tpp-events-out` Kafka emitter is swapped to the in-memory connector so no broker is needed to
 * boot (same as `TppRegistryBootSmokeIT`).
 *
 * IMPORTANT: if psd2 changes its contract, regenerate the pact JSON on the consumer side and commit
 * it in the same PR, or this test verifies a stale contract.
 * `@IgnoreNoPactsToVerify(ignoreIoErrors)` makes a missing/unreadable pact a skip, not a failure.
 */
@QuarkusTest
@QuarkusTestResource(TppRegistryPactProviderVerificationTest.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.tppregistry.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-tpp-registry-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class TppRegistryPactProviderVerificationTest {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("tpp-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // context is null on the @IgnoreNoPactsToVerify dummy invocation — skip gracefully.
        context?.verifyInteraction()
    }

    @State("no registry entry exists for the pact unknown-TPP id")
    fun unknownTppIsNotRegistered() {
        // No setup: nothing registers CZ-CNB-PACT-UNREGISTERED. Declared so the state is an
        // explicit part of the contract rather than a name pact-jvm passes over silently — an
        // unhandled state is not an error, which is how #468's missing states stayed invisible.
    }

    @State("the TPP registry has an ACTIVE AISP with an unexpired QWAC")
    fun activeAispWithValidQwacIsSeeded() {
        // No setup: V1__init.sql already seeds CZ-CNB-TEST-AISP as ACTIVE/AISP with a NULL
        // qwac_expires_at, which TppRegistryService.checkAuthorization treats as never-expired.
        // Declared for the same reason as the state above — an explicit, visible no-op.
    }
}
