// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider verification for the interactions that must be served UNAUTHENTICATED.
 *
 * ## Why this is a second class rather than one more `@State` method
 *
 * [TransactionPactFolderProviderVerificationTest] carries a class-level
 * `@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])`, which authenticates every
 * request it makes. That is what the positive interactions need and it is exactly what these
 * interactions must not have: swift-service, sdd-service and interest-service each publish a
 * "POST the … debit with a missing or expired token" case that expects **401**, and no state
 * handler can undo a class-level security context. A state method alone would have produced an
 * authenticated request and a 200 where the pact demands a 401.
 *
 * `@TestSecurity` is absent here on purpose, so the request arrives anonymous and
 * `TransactionResource`'s `@RolesAllowed` answers 401 — the behaviour the consumers encoded.
 *
 * ## Why the split is safe
 *
 * `CLAUDE.md` warns against two verification classes for one provider. The collision it describes
 * is two BROKER-sourced classes, each fetching every pact the broker holds. These two are both
 * `@PactFolder` and carry disjoint `@PactFilter` state regexes, so each interaction is verified by
 * exactly one class and none is verified twice. pact-jvm 4.7.3 matches a filter value against the
 * provider-state name with `String.matches`, i.e. a full-match Java regex — measured from the
 * bytecode of `InteractionFilter$ByProviderState`, which is why the negative lookahead on the
 * sibling class is a supported construct rather than a hopeful one.
 *
 * ## The assumption this rests on
 *
 * `ByProviderState` excludes an interaction with NO provider state (its predicate is `anyMatch`
 * over an empty list). Every committed transaction-service interaction declares one today, and
 * [TransactionPactStateCoverageTest] fails the build if that ever stops being true — otherwise a
 * stateless interaction would be silently verified by neither class, which is the same
 * "green about work it never did" shape this whole replay exists to prevent.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
@Provider("openbank-transaction-service")
@PactFolder("../pacts")
@PactFilter(NEGATIVE_AUTH_STATE)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class TransactionNegativeAuthPactVerificationTest {

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
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // Intentionally empty: the state IS the absence of an authenticated identity, and this
        // class provides that by not declaring @TestSecurity. Declared rather than left implicit
        // because pact-jvm fails the interaction outright when no handler matches the state name —
        // which is how #8697 turned main red (issue #8984).
    }
}

/**
 * The provider-state name the three "missing or expired token" interactions declare. Shared with
 * [TransactionPactFolderProviderVerificationTest], whose filter excludes exactly this value: one
 * literal, so the two filters cannot drift apart into a gap (an interaction verified by neither)
 * or an overlap (verified twice).
 */
const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
