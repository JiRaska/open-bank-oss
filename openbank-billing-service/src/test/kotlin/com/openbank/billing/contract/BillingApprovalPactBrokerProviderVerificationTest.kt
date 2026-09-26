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
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime

/** Broker-sourced twin that publishes billing verification for deployment safety on main. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@Provider("openbank-billing-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@TestSecurity(user = "pact-operator", roles = ["ROLE_OPERATOR"])
class BillingApprovalPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

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
}
