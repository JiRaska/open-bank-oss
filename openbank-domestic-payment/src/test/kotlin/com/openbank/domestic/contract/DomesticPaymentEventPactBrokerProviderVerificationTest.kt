// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/** Broker-sourced twin; the folder class remains the pre-merge gate. */
@Provider("openbank-domestic-payment")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DomesticPaymentEventPactBrokerProviderVerificationTest {

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
    fun produceDelegatedStatusChanged(): MessageAndMetadata =
        DomesticPaymentStatusPactFixture.message(DomesticPaymentStatus.SENT_TO_CLEARING, DomesticPaymentStatus.SETTLED)

    @State(DelegatedSpendFinalizedAbsentPactFixture.PROVIDER_STATE)
    fun pendingReservationWithoutPayment() = Unit

    @PactVerifyProvider(DelegatedSpendFinalizedAbsentPactFixture.INTERACTION)
    fun produceDelegatedSpendFinalizedAbsent(): MessageAndMetadata = DelegatedSpendFinalizedAbsentPactFixture.message()

    // Terminal outcomes the delegation-service consumer settles a reservation from (#16000). Each
    // is a legal domain transition from the status the rail actually leaves from.
    @State("a delegated domestic payment has become REJECTED")
    fun delegatedPaymentRejected() = Unit

    @PactVerifyProvider("a delegated domestic payment REJECTED event")
    fun produceDelegatedRejected(): MessageAndMetadata =
        DomesticPaymentStatusPactFixture.message(DomesticPaymentStatus.VALIDATED, DomesticPaymentStatus.REJECTED)

    @State("a delegated domestic payment has become CANCELLED")
    fun delegatedPaymentCancelled() = Unit

    @PactVerifyProvider("a delegated domestic payment CANCELLED event")
    fun produceDelegatedCancelled(): MessageAndMetadata =
        DomesticPaymentStatusPactFixture.message(DomesticPaymentStatus.VALIDATED, DomesticPaymentStatus.CANCELLED)

    @State("a delegated domestic payment has become RETURNED")
    fun delegatedPaymentReturned() = Unit

    @PactVerifyProvider("a delegated domestic payment RETURNED event")
    fun produceDelegatedReturned(): MessageAndMetadata =
        DomesticPaymentStatusPactFixture.message(DomesticPaymentStatus.SENT_TO_CLEARING, DomesticPaymentStatus.RETURNED)
}
