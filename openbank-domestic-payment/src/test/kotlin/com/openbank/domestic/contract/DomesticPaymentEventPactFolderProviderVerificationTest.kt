// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/** Always-on git-pact replay for the delegated reservation status-event consumer. */
@Provider("openbank-domestic-payment")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DomesticPaymentEventPactFolderProviderVerificationTest {

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        context?.target = MessageTestTarget(listOf("com.openbank.domestic.contract"))
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State(DomesticPaymentStatusPactFixture.PROVIDER_STATE)
    fun delegatedPaymentChangedStatus() = Unit

    @PactVerifyProvider(DomesticPaymentStatusPactFixture.INTERACTION)
    fun produceDelegatedStatusChanged(): String = DomesticPaymentStatusPactFixture.payload()

    @State(DelegatedSpendFinalizedAbsentPactFixture.PROVIDER_STATE)
    fun pendingReservationWithoutPayment() = Unit

    @PactVerifyProvider(DelegatedSpendFinalizedAbsentPactFixture.INTERACTION)
    fun produceDelegatedSpendFinalizedAbsent(): String = DelegatedSpendFinalizedAbsentPactFixture.payload()
}
