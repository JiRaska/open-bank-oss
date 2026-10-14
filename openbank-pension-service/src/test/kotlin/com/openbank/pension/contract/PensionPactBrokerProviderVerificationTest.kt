// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.pension.application.onboarding.TransferRequestRepository
import com.openbank.pension.infrastructure.fund.InMemoryFundAdministrationAdapter
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.security.Principal

/**
 * Broker-sourced twin of [PensionPactProviderVerificationTest] (ADR-0334 S6): `@PactFolder` never
 * contacts the broker, so without this class no verification result or provider version for
 * pension-service would ever be published and `can-i-deploy` could not answer for customer-edge.
 *
 * The sanctioned pair (root CLAUDE.md, Pact): the folder class runs on every PR; this one is gated
 * on `pactbroker.url`, which only main-push supplies (the broker has no public ingress, ADR-0056).
 * Same states and seed as the folder class; the missing-identity interaction clears the class-level
 * test identity so the recorded 401 verifies.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "edge-relay-subject"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = "service-account-openbank-edge"),
    ],
)
@Provider("openbank-pension-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class PensionPactBrokerProviderVerificationTest {
    @Inject
    lateinit var register: InMemoryFundAdministrationAdapter

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context != null) {
            val states = context.interaction.providerStates.map { it.name }
            when {
                NEGATIVE_AUTH_STATE in states -> testIdentityAssociation.setTestIdentity(null)
                PARTICIPANT_REPORTING_STATE in states -> testIdentityAssociation.setTestIdentity(
                    QuarkusSecurityIdentity.builder()
                        .setPrincipal(Principal { "service-account-openbank-tax-reporting" })
                        .addRole("ROLE_API")
                        .build(),
                )
            }
        }
        context?.verifyInteraction()
    }

    @State("the customer party holds an active pension contract")
    fun activeContract() {
        PactContractSeed.reset()
        PactContractSeed.insertActive()
    }

    @State("the customer party holds no pension contract")
    fun noContract() {
        PactContractSeed.reset()
    }

    /** The overview's valuation and the transactions page read the in-memory unit register (%test). */
    @State(PactContractSeed.UNITS_STATE)
    fun contractWithUnits() {
        PactContractSeed.reset()
        PactContractSeed.insertActive()
        PactContractSeed.seedUnits(register)
    }

    @Inject
    lateinit var transfers: TransferRequestRepository

    @State(PactContractSeed.AWAITING_CONSENT_STATE)
    fun transferAwaitingConsent() = PactContractSeed.insertAwaitingConsent(transfers)

    @State(PARTICIPANT_REPORTING_STATE)
    fun participantReporting() = PactContractSeed.reset()

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }
}
