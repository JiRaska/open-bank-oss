// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.referral.domain.ReferralEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID

/**
 * Broker-side provider verification for referral-service, published-result counterpart to
 * [ReferralEventPactFolderProviderVerificationTest].
 *
 * **Why this exists.** Without this class, referral-service would replay the committed pact from
 * disk on every PR but NEVER publish a verification result — `pact-verification-reconcile.yml`
 * would report "openbank-referral-service has never published a version on main", `can-i-deploy`
 * (ADR-0092) would see no verified pact between loyalty-service and the referral-service running
 * in the environment, and every loyalty-service deploy would block permanently. Measured on
 * 2026-08-02 with the identical aml-service gap: the deploy of the consumer service was blocked
 * for weeks while CI stayed green.
 *
 * `@EnabledIfSystemProperty(named = "pactbroker.url")` keeps it a no-op locally and on the PR
 * lane, where no broker is configured (ADR-0056) — the folder twin is the pre-merge gate.
 *
 * This class MUST stay the mirror of [ReferralEventPactFolderProviderVerificationTest]: the same
 * `@State` and `@PactVerifyProvider` methods, producing the same message. A state the broker
 * replay cannot satisfy fails with `MissingStateChangeMethod` and publishes a FAILURE, which is
 * strictly worse than publishing nothing.
 */
@Provider("openbank-referral-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class ReferralEventPactBrokerProviderVerificationTest {

    // Must byte-match the folder twin's mapper config: a drift between the two lets one lane pass
    // and the other publish a failure for the same contract.
    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        if (context == null) return
        // Same JDK 25+ ClassGraph fix as the folder twin.
        if (context.interaction.isAsynchronousMessage()) {
            context.target = MessageTestTarget(listOf("com.openbank.referral.contract"))
        }
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("a referral invite has been qualified")
    fun qualifiedInvite() {
        // No setup: the message is produced deterministically by the @PactVerifyProvider below.
    }

    @PactVerifyProvider("a Qualified event for the qualified invite")
    fun qualifiedEvent(): String {
        // Must byte-match the folder twin's message: a drift between the two lets one lane pass
        // and the other publish a failure for the same contract.
        val event = ReferralEvent.Qualified(
            eventId = UUID.fromString("3f8b2c1e-7d4a-4c9b-9e1f-2a5b6c7d8e9f"),
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            programId = UUID.fromString("8a9b0c1d-2e3f-4a5b-6c7d-8e9f0a1b2c3d"),
            inviteId = UUID.fromString("1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e"),
            referrerPartyId = UUID.fromString("9e8f7a6b-5c4d-4e3f-8a2b-1c0d9e8f7a6b"),
            refereePartyId = UUID.fromString("7a6b5c4d-3e2f-4a1b-9c8d-0e1f2a3b4c5d"),
            qualificationEventId = "account.opened",
        )
        return objectMapper.writeValueAsString(event)
    }
}
