// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.BorrowerDistressPort
import com.openbank.lending.application.port.out.CreditOffersConsentPort
import com.openbank.lending.domain.model.BorrowerDistressSignals
import com.openbank.lending.domain.model.CreditOfferDecision
import com.openbank.lending.domain.model.CreditOfferSuppressionCode
import com.openbank.lending.domain.model.OfferSurface
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0269 rules 1 and 2 at the service seam. The domain evaluator is tested exhaustively in
 * `CreditOfferEligibilityTest`; what is proved here is the part a unit test of a pure function
 * cannot reach — that an unreachable upstream refuses instead of degrading open.
 */
class CreditOfferEligibilityServiceTest {

    // NOTE: every test below is `= runBlocking<Unit> { ... }`, never a bare `= runBlocking { ... }`.
    // An expression-bodied test that returns a value is silently SKIPPED by JUnit 5 ("@Test method
    // must not return a value") and the build still reports success — a vacuous green. The explicit
    // Unit is what makes these assertions actually run.

    private val partyId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)

    private val healthy = BorrowerDistressSignals(
        hasArrears = false,
        hasNegativeBalance = false,
        hasEnforcementOrder = false,
        hasInsolvencyProceeding = false,
        inHardshipArrangement = false,
        lastAffordabilityFailureAt = null,
        bufferDays = 90,
        lastCreditContactAt = null,
        inputsChangedSinceLastContact = true,
        complete = true,
    )

    private fun service(
        consent: Boolean?,
        consentThrows: Boolean = false,
        signals: BorrowerDistressSignals = healthy,
        signalsThrow: Boolean = false,
    ) = CreditOfferEligibilityService(
        consent = object : CreditOffersConsentPort {
            override suspend fun hasCreditOffersConsent(partyId: UUID): Boolean? {
                if (consentThrows) error("consent-service unreachable")
                return consent
            }
        },
        distress = object : BorrowerDistressPort {
            override suspend fun signalsFor(partyId: UUID): BorrowerDistressSignals {
                if (signalsThrow) error("360 read failed")
                return signals
            }
        },
        clock = clock,
    )

    private fun codeOf(decision: CreditOfferDecision): CreditOfferSuppressionCode? =
        (decision as? CreditOfferDecision.Suppressed)?.code

    @Test
    fun `consent granted and no distress yields an allowed offer`() = runBlocking<Unit> {
        val decision = service(consent = true).evaluate(partyId, OfferSurface.PUSH)
        assertThat(decision).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }

    @Test
    fun `consent absent suppresses before any distress signal is read`() = runBlocking<Unit> {
        // signalsThrow proves the distress port is never consulted: if it were, this would surface
        // as SIGNALS_UNAVAILABLE instead of CONSENT_ABSENT.
        val decision = service(consent = false, signalsThrow = true).evaluate(partyId, OfferSurface.PUSH)
        assertThat(codeOf(decision)).isEqualTo(CreditOfferSuppressionCode.CONSENT_ABSENT)
    }

    @Test
    fun `an unknown consent state refuses rather than assuming denial or grant`() = runBlocking<Unit> {
        val decision = service(consent = null).evaluate(partyId, OfferSurface.PUSH)
        assertThat(codeOf(decision)).isEqualTo(CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE)
    }

    @Test
    fun `a thrown consent read fails closed`() = runBlocking<Unit> {
        val decision = service(consent = true, consentThrows = true).evaluate(partyId, OfferSurface.PUSH)
        assertThat(codeOf(decision)).isEqualTo(CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE)
    }

    @Test
    fun `a thrown distress read fails closed even with consent granted`() = runBlocking<Unit> {
        val decision = service(consent = true, signalsThrow = true).evaluate(partyId, OfferSurface.PUSH)
        assertThat(codeOf(decision)).isEqualTo(CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE)
    }

    @Test
    fun `incomplete signals fail closed even when every flag reads healthy`() = runBlocking<Unit> {
        val decision = service(
            consent = true,
            signals = healthy.copy(complete = false),
        ).evaluate(partyId, OfferSurface.PUSH)
        assertThat(codeOf(decision)).isEqualTo(CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE)
    }

    @Test
    fun `distress suppresses a consenting customer`() = runBlocking<Unit> {
        val decision = service(
            consent = true,
            signals = healthy.copy(hasArrears = true),
        ).evaluate(partyId, OfferSurface.PUSH)
        assertThat(codeOf(decision)).isEqualTo(CreditOfferSuppressionCode.ARREARS)
    }

    // ── PULL: the customer asked ──────────────────────────────────────────────

    @Test
    fun `a pull needs no consent — the bank is answering, not initiating`() = runBlocking<Unit> {
        val decision = service(consent = false).evaluate(partyId, OfferSurface.PULL)
        assertThat(decision).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }

    @Test
    fun `a pull is still stopped by the distress floor`() = runBlocking<Unit> {
        val decision = service(consent = false, signals = healthy.copy(hasArrears = true))
            .evaluate(partyId, OfferSurface.PULL)
        assertThat(codeOf(decision)).isEqualTo(CreditOfferSuppressionCode.ARREARS)
    }

    @Test
    fun `a pull ignores contact frequency — a cap must not silence an answer that was asked for`() = runBlocking<Unit> {
        val recentlyContacted = healthy.copy(lastCreditContactAt = clock.instant().minusSeconds(3600))
        val push = service(consent = true, signals = recentlyContacted).evaluate(partyId, OfferSurface.PUSH)
        val pull = service(consent = true, signals = recentlyContacted).evaluate(partyId, OfferSurface.PULL)
        assertThat(codeOf(push)).isEqualTo(CreditOfferSuppressionCode.FREQUENCY_CAP)
        assertThat(pull).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }
}
