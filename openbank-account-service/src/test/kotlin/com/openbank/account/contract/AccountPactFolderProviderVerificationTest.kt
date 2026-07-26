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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.domain.event.AccountCreatedEvent
import com.openbank.account.domain.model.AccountType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Git-pact provider verification for account-service — the half that actually runs before a merge
 * (issue #2327, gated by `check-pact-provider-replay.py` per #2338).
 *
 * account-service is the provider for two committed pacts, both message-only: balance-service's
 * `AccountCreated` and notification-service's `TRANSACTION_COMPLETED`. Its only verification class
 * was [AccountEventPactProviderVerificationTest] — `@PactBroker`-sourced and
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated. On a pull request that property is empty
 * (`_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks `PACT_BROKER_URL` off
 * main-push, because the broker has no public ingress, ADR-0056), so it skipped and both contracts
 * were replayed only AFTER the merge.
 *
 * ## Additive, not a replacement — this class does NOT undo #1166
 *
 * The broker twin stays exactly as it is. Flipping it to `@PactFolder` is precisely what #372 did
 * and #1166 reverted: nothing then pulled the consumers' pacts back out of the broker to verify
 * them and publish a result, and since `can-i-deploy` reads the broker and nothing else, it saw
 * "no verified pact" for every consumer of account-service and blocked their deploys —
 * notification-service #1180 and #1303 both merged clean, built green, then failed the gate. Git
 * source for the PR lane, broker source for the published result: the pair
 * openbank-ledger-service carries. Two `@Provider` classes collide only when BOTH pull from the
 * broker, since each then fetches every pact it holds.
 *
 * ## Cheap, because there is nothing to boot
 *
 * Both pacts are message-only, so a [MessageTestTarget] alone is correct and no Quarkus instance
 * or Testcontainer is needed — this adds a plain JVM test, not another `@QuarkusTest`. If an HTTP
 * consumer contract against account-service is ever added, this needs party-service's
 * per-interaction target dispatch, and so does the broker twin.
 *
 * ## Upkeep
 *
 * A deliberate duplicate of the broker twin's body: same `@State` handlers and same
 * [PactVerifyProvider] producers. A change to one belongs in the other, or the same contract
 * passes from git and fails from the broker (or the reverse).
 */
@Provider("openbank-account-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class AccountPactFolderProviderVerificationTest {

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
