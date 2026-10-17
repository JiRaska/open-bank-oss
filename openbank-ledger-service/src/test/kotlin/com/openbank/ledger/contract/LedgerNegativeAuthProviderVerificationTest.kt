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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider verification for the interactions that must be served UNAUTHENTICATED (ADR-0279).
 *
 * ## Why this is a second class rather than one more `@State` method
 *
 * [LedgerPactProviderVerificationTest] carries a class-level `@TestSecurity(user =
 * "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])`, which authenticates every request it
 * makes — exactly what the positive interactions need and exactly what
 * `TreasuryNostroLedgerReadPactConsumerTest`'s "no valid M2M identity is presented" interaction
 * must not have: no state handler can undo a class-level security context, so a state method alone
 * would produce an authenticated request and a 200 where the pact demands a 401.
 *
 * `@TestSecurity` is absent here on purpose, so the request arrives anonymous and
 * `LedgerResource`'s `@RolesAllowed` answers 401 — the behaviour the consumer encoded. Same split
 * as `TransactionPactFolderProviderVerificationTest` / `TransactionNegativeAuthProviderVerificationTest`
 * in openbank-transaction-service, which this class mirrors.
 *
 * ## Why the split is safe
 *
 * `CLAUDE.md` warns against two verification classes for one provider — the collision it
 * describes is two BROKER-sourced classes, each fetching every pact the broker holds. These two
 * are both `@PactFolder` and carry disjoint `@PactFilter` state regexes
 * ([LedgerPactProviderVerificationTest] excludes exactly [NEGATIVE_AUTH_STATE]), so each
 * interaction is verified by exactly one class and none is verified twice.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@Provider("openbank-ledger-service")
@PactFolder("../pacts")
@PactFilter(NEGATIVE_AUTH_STATE)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class LedgerNegativeAuthProviderVerificationTest {

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
        // because pact-jvm fails the interaction outright when no handler matches the state name.
    }
}

/**
 * The provider-state name `TreasuryNostroLedgerReadPactConsumerTest`'s "missing or expired token"
 * interaction declares. Shared with [LedgerPactProviderVerificationTest], whose filter excludes
 * exactly this value: one literal, so the two filters cannot drift apart into a gap (an
 * interaction verified by neither) or an overlap (verified twice).
 */
const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
