// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.out.BorrowerDistressPort
import com.openbank.lending.application.port.out.CreditOffersConsentPort
import com.openbank.lending.application.usecase.CreditOfferEligibilityService
import com.openbank.lending.domain.model.BorrowerDistressSignals
import com.openbank.lending.domain.model.CourtRegisterSignalState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The wire surface of ADR-0269's push gate.
 *
 * These assert the two properties a caller depends on and cannot check for itself: that a refusal
 * arrives as a readable decision rather than an error to be guessed at, and that the PUSH surface
 * is not negotiable — a caller must not be able to obtain the PULL answer, which skips the consent
 * half.
 */
class CreditOfferEligibilityResourceTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), ZoneOffset.UTC)
    private val party = UUID.randomUUID()

    private val healthy = BorrowerDistressSignals(
        hasArrears = false,
        hasNegativeBalance = false,
        enforcementSignal = CourtRegisterSignalState.CLEAR,
        insolvencySignal = CourtRegisterSignalState.CLEAR,
        inHardshipArrangement = false,
        lastAffordabilityFailureAt = null,
        bufferDays = 90,
        monthsObserved = 12,
        lastCreditContactAt = null,
        inputsChangedSinceLastContact = true,
        complete = true,
    )

    private fun resource(consent: Boolean, signals: BorrowerDistressSignals = healthy) = CreditOfferEligibilityResource(
        CreditOfferEligibilityService(
            consent = object : CreditOffersConsentPort {
                override suspend fun hasCreditOffersConsent(partyId: UUID) = consent
            },
            distress = object : BorrowerDistressPort {
                override suspend fun signalsFor(partyId: UUID) = signals
            },
            clock = clock,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun body(consent: Boolean, signals: BorrowerDistressSignals = healthy): Map<String, Any?> =
        resource(consent, signals).eligibility(party).await().indefinitely().entity as Map<String, Any?>

    @Test
    fun `a consenting party with no distress may be offered credit`() {
        assertThat(body(consent = true)).containsEntry("allowed", true).containsEntry("reasonCode", null)
    }

    @Test
    fun `no consent is a refusal that names itself`() {
        // The caller has to be able to count "never opted in" apart from "in arrears": one is a
        // preference and the other is a conduct fact, and a single "declined" number hides which.
        assertThat(body(consent = false))
            .containsEntry("allowed", false)
            .containsEntry("reasonCode", "CONSENT_ABSENT")
    }

    @Test
    fun `consent does not lift the distress floor`() {
        // ADR-0269 rule 2 is the whole point of this route: a customer who agreed to hear about
        // credit and is in arrears must still not be marketed to.
        assertThat(body(consent = true, signals = healthy.copy(hasArrears = true)))
            .containsEntry("allowed", false)
            .containsEntry("reasonCode", "ARREARS")
    }

    @Test
    fun `an incomplete signal set refuses rather than guesses`() {
        assertThat(body(consent = true, signals = healthy.copy(complete = false)))
            .containsEntry("allowed", false)
            .containsEntry("reasonCode", "SIGNALS_UNAVAILABLE")
    }

    @Test
    fun `a refusal is a 200, so a caller cannot mistake it for a transport failure`() {
        // A 4xx would invite "treat errors as allow" in a caller's retry path. The refusal is a
        // successful answer to the question asked.
        assertThat(resource(consent = false).eligibility(party).await().indefinitely().status).isEqualTo(200)
    }

    @Test
    fun `the answer carries the policy version that produced it`() {
        assertThat(body(consent = true)["policyVersion"]).isNotNull()
    }
}
