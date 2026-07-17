// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
 * `@PactBroker` (not `@PactFolder`) — the same fix #1166 applied to party-service, for the same
 * reason. `_service-ci.yml` publishes every consumer's pacts to the broker on a main push, but
 * #372 (2026-07-07) had switched this class to `@PactFolder("../pacts")`, so nothing ever pulled
 * those pacts BACK OUT of the broker to verify them and publish a result. `can-i-deploy` reads the
 * broker and nothing else, so it permanently saw "no verified pact" for every consumer of
 * account-service and blocked their deploys — confirmed live: notification-service #1180 and #1303
 * both merged clean, built green, then failed the gate with "There is no verified pact between
 * openbank-notification-service and openbank-account-service".
 *
 * `@EnabledIfSystemProperty` keeps it a no-op locally and on the PR lane, where no broker is
 * configured — matching every other broker-based provider test in the fleet.
 *
 * A [MessageTestTarget] alone is correct here: both pacts naming account-service as provider are
 * message-only (balance-service's AccountCreated, notification-service's TRANSACTION_COMPLETED),
 * so unlike `PartyEventPactProviderVerificationTest` there is no HTTP interaction to dispatch to
 * and no Quarkus instance to boot. If an HTTP consumer contract against account-service is ever
 * added, this needs party-service's per-interaction target dispatch.
 *
 * IMPORTANT: if `AccountCreatedMessagePactConsumerTest` (openbank-balance-service) changes the
 * contract, regenerate the pact JSON (`./gradlew :openbank-balance-service:test --tests
 * "*AccountCreatedMessagePactConsumerTest*"`) and commit the updated `pacts/openbank-balance-
 * service-openbank-account-service.json` in the same PR, or this test will fail against a stale
 * contract.
 *
 * `@IgnoreNoPactsToVerify(ignoreIoErrors)` makes a missing/unreadable pact a skip, not a failure.
 */
@Provider("openbank-account-service")
@PactBroker(enablePendingPacts = "true")
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
            occurredAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
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
