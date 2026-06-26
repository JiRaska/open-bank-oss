// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.model.AccountType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Provider-side verification for async message contracts published by account-service
 * (ADR-0063 P1+P2 → ADR-0092). Covers:
 * - AccountCreated event (P1) consumed by balance-service
 * - TRANSACTION_COMPLETED notification request (P2 Batch C) consumed by notification-service
 *
 * Unlike the HTTP provider verification, this needs no running Quarkus instance: a
 * [MessageTestTarget] asks the [PactVerifyProvider] method for the message the provider would
 * emit, and Pact checks it against the consumer contract. Messages are built from real domain
 * types and serialized with the same Jackson modules so the contract verifies the real wire shape.
 *
 * Pacts are fetched from the Pact Broker (`@PactBroker`, configured via `pactbroker.*` system
 * properties from CI `-D`) and the result published back for `can-i-deploy`. Gated on
 * `pactbroker.url`: skipped locally, where committed `pacts/` (git-pact) stays the fallback.
 */
@Provider("openbank-account-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class AccountEventPactProviderVerificationTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        // Limit the @PactVerifyProvider scan to this package — the default classpath-wide scan
        // (ClassGraph) throws on the JDK 25 toolchain.
        context?.target = MessageTestTarget(listOf("com.openbank.account.contract"))
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("an account has been created")
    fun accountHasBeenCreated() {
        // No setup: the message is produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("an AccountCreated event")
    fun produceAccountCreatedEvent(): String {
        val event = AccountCreatedEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            accountNumber = "CZ6508000000192000145399",
            accountType = AccountType.CURRENT,
            partyId = UUID.randomUUID(),
            productId = UUID.randomUUID(),
            currency = "CZK",
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("account-service has posted an incoming credit")
    fun accountHasPostedIncomingCredit() {
        // No setup: notification request is produced deterministically below.
    }

    @PactVerifyProvider("a TRANSACTION_COMPLETED notification request")
    fun produceTransactionCompletedNotification(): String {
        val partyId = UUID.randomUUID()
        val request = linkedMapOf(
            "partyId" to partyId.toString(),
            "channel" to "PUSH",
            "template" to "TRANSACTION_COMPLETED",
            "recipient" to partyId.toString(),
            "variables" to mapOf("amount" to "50.00", "currency" to "CZK"),
        )
        return objectMapper.writeValueAsString(request)
    }
}
