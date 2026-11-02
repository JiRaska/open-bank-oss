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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.referral.domain.ReferralEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID

/**
 * Git-pact provider verification for the referral events consumed by loyalty-service
 * (`openbank.loyalty.events`'s sibling `openbank.referral.qualified.v1`) — the FIRST provider
 * replay referral-service has ever had.
 *
 * Message-only, so this is a plain JVM test: a [MessageTestTarget] asks the [PactVerifyProvider]
 * method for the message the provider would emit, and Pact checks it against the consumer
 * contract. No Quarkus boot, no Testcontainers — the messages are built from the real
 * [ReferralEvent] domain types and serialized with the same Jackson idiom as
 * `ReferralService` (`objectMapper.writeValueAsString`), so the contract verifies the real wire
 * shape rather than a hand-rewritten copy of it.
 *
 * `@PactFolder`, ungated, so it runs on the pull request: a `@PactBroker` class is
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated and the PR lane has no broker (ADR-0056), which
 * would replay the contract only after the merge. `check-pact-provider-replay.py` (issue #2327)
 * fails a committed pact with no always-running `@PactFolder` replay — this class is what keeps
 * `pacts/openbank-loyalty-service-openbank-referral-service.json` off that list.
 *
 * ## One HALF of a deliberate pair
 *
 * [ReferralEventPactBrokerProviderVerificationTest] is the `@PactBroker` twin and it is required:
 * this class is the PR-lane gate, the twin is the only thing that publishes a verification
 * RESULT, which is all `can-i-deploy` (ADR-0092) reads. A provider carrying only this class has
 * never published a version on main, so every consumer reads as unverified and its deploys block
 * permanently. Any `@State` or `@PactVerifyProvider` added here MUST be added to the twin in the
 * same commit.
 */
@Provider("openbank-referral-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class ReferralEventPactFolderProviderVerificationTest {

    // Quarkus' runtime ObjectMapper renders dates as ISO-8601 strings (it disables
    // WRITE_DATES_AS_TIMESTAMPS fleet-wide); a stock jacksonObjectMapper() would emit Instant as a
    // numeric epoch and the contract would verify a wire shape the real producer never sends.
    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        if (context == null) return
        // Limit the @PactVerifyProvider scan to this package — the default classpath-wide scan
        // (ClassGraph) throws on the JDK 25+ toolchain (same fix as account-service's replay).
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
