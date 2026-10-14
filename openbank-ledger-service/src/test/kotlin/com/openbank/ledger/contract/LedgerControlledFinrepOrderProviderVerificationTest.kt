// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.contract

import au.com.dius.pact.core.model.SynchronousRequestResponse
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactSource
import com.openbank.ledger.fixtures.ControlledFinrepOrderPactLoader
import com.openbank.ledger.fixtures.ControlledFinrepOrderResults
import com.openbank.ledger.fixtures.FinrepUniqueInvocationProvider
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import javax.sql.DataSource

/** Opt-in controlled broker replay in both orders. No external broker publication or issue closure. */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.ledger.it.PostgresRedpandaTestResource::class,
    restrictToAnnotatedClass = true,
)
@TestSecurity(user = "historical-finrep-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-ledger-service")
@PactSource(ControlledFinrepOrderPactLoader::class)
@EnabledIfSystemProperty(named = "finrep.order.enabled", matches = "true")
class LedgerControlledFinrepOrderProviderVerificationTest {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var testIdentityAssociation: TestIdentityAssociation

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    private var plan = FinrepFrozenMonthPactSeed.REPORTING_MONTH to true

    @BeforeEach
    fun configureTarget(context: PactVerificationContext) {
        val paths = context.pact.interactions.filter {
            it.providerStates.any { state -> state.name == FROZEN_STATE }
        }.map { (it as SynchronousRequestResponse).request.path }
        plan = FinrepFrozenMonthPactSeed.requestPlan(paths)
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(FinrepUniqueInvocationProvider::class)
    fun verifyPact(context: PactVerificationContext) {
        if (context.interaction.providerStates.any { it.name == NEGATIVE_AUTH_STATE }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context.verifyInteraction()
        ControlledFinrepOrderResults.record(context.pact, context.interaction)
    }

    @State(FROZEN_STATE)
    fun frozenMonth(parameters: Map<String, Any>) {
        FinrepFrozenMonthPactSeed.validateStateParameters(plan, parameters)
        FinrepFrozenMonthPactSeed.seed(dataSource, plan.first, plan.second, resetFixture = true)
    }

    @State(NEGATIVE_AUTH_STATE)
    fun missingIdentity() = Unit

    private companion object {
        @JvmStatic
        @AfterAll
        fun verifyFullSequence() = ControlledFinrepOrderResults.finish()

        const val FROZEN_STATE = "ledger has frozen monthly trial balance for the reporting date"
    }
}
