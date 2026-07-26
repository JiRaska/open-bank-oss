// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side Pact verification for `openbank-clearing-simulator`'s inbound `pacs.008` endpoint
 * (issue #468 edge 4 — "clearing inbound"). First pact wiring in this module: every payment rail
 * (sepa-instant, sepa-payment, swift-service, domestic-payment) submits here via
 * `ClearingSimulatorClient.submitCreditTransfer`; swift-service is the first consumer with a
 * committed contract ([com.openbank.swift.contract.ClearingSimulatorPactConsumerTest]).
 *
 * Git-pact (`@PactFolder`, resolved relative to this module's working directory at `../pacts` =
 * the monorepo-root `pacts/` dir), matching `LedgerPactProviderVerificationTest`'s pattern — always
 * runs, no broker, no CI secret required. `@TestSecurity` matches the endpoint's
 * `@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")`, mirroring how every real rail
 * authenticates (`ClearingSimulatorApiIT`'s own `@TestSecurity(user = "rail", roles =
 * ["ROLE_API"])`).
 */
@QuarkusTest
@TestSecurity(user = "rail", roles = ["ROLE_API"])
@Provider("openbank-clearing-simulator")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class ClearingSimulatorPactProviderVerificationTest {

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

    @State("the clearing simulator is available")
    fun stateSimulatorAvailable() {
        // Stateless, deterministic counterparty (no DB) — no setup needed. A well-formed transfer
        // always settles (ACSC) unless its amount triggers a deterministic reject.
    }
}
