// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime

/** Broker-sourced twin that publishes billing verification for deployment safety on main. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_billing_it")],
)
@Provider("openbank-billing-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@TestSecurity(user = "pact-operator", roles = ["ROLE_OPERATOR"])
class BillingApprovalPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

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
        installApprovalStoreFixture()
        context?.target = HttpTestTarget("localhost", port.toInt())
    }

    @State("a pending billing approval exists")
    fun pendingBillingApprovalExists() = Unit

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context?.interaction?.providerStates?.any { it.name == NO_IDENTITY_STATE } == true) {
            testIdentity.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    private fun installApprovalStoreFixture() {
        val store = mockk<ApprovalStore>()
        coEvery { store.findPending(50) } returns listOf(PENDING_APPROVAL)
        QuarkusMock.installMockForType(store, ApprovalStore::class.java)
    }

    private companion object {
        val PENDING_APPROVAL = PendingApproval(
            id = "billing-approval-4",
            action = "billing.post",
            resourceId = "fee-4",
            makerId = "maker.billing",
            status = ApprovalStatus.PENDING,
            createdAt = OffsetDateTime.parse("2026-08-31T11:00:42Z"),
        )
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
