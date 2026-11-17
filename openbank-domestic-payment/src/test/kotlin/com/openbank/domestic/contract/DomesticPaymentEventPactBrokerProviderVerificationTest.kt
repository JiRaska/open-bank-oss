// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.integration.DomesticPaymentOutboxAtomicityIT
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-sourced twin of [DomesticPaymentEventPactFolderProviderVerificationTest] with the same
 * `@State` handlers; the folder class remains the pre-merge gate. Replays, for every consumer of domestic-payment: the delegation-service event
 * pacts (messages) and customer-edge's reservation-bound create (HTTP, #16000 part 4), including
 * its missing-identity 401. The replay runs as the verified customer-edge identity, the only one
 * the bound route admits; the 401 interaction clears it.
 */
@QuarkusTest
@TestProfile(BoundCreatePactProfile::class)
@TestSecurity(user = DomesticBoundCreatePactFixture.EDGE_USER, roles = ["ROLE_OPERATOR", "ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = DomesticBoundCreatePactFixture.EDGE_SUB),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = DomesticBoundCreatePactFixture.EDGE_USER),
    ],
)
@QuarkusTestResource(DomesticPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
@Provider("openbank-domestic-payment")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DomesticPaymentEventPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var bindingRepository: DelegatedSpendBindingRepository

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = if (context.interaction.isAsynchronousMessage()) {
            MessageTestTarget(listOf("com.openbank.domestic.contract"))
        } else {
            HttpTestTarget("localhost", testPort.toInt())
        }
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context != null &&
            context.interaction.providerStates.any { it.name == DomesticBoundCreatePactFixture.NEGATIVE_AUTH_STATE }
        ) {
            testIdentityAssociation.setTestIdentity(null)
        }
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

    @State(DomesticBoundCreatePactFixture.BOUND_STATE)
    fun reservedReservationProjected() {
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) {
                bindingRepository.applySnapshot(DomesticBoundCreatePactFixture.snapshot())
            }
        }
    }

    /** The state IS the absence of an identity, which [verifyPacts] arranges. */
    @State(DomesticBoundCreatePactFixture.NEGATIVE_AUTH_STATE)
    fun noValidM2mIdentity() = Unit
}
