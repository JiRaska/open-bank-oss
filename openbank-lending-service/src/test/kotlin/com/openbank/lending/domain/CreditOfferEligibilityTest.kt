// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain

import com.openbank.lending.domain.model.BorrowerDistressSignals
import com.openbank.lending.domain.model.CreditOfferDecision
import com.openbank.lending.domain.model.CreditOfferEligibility
import com.openbank.lending.domain.model.CreditOfferPolicy
import com.openbank.lending.domain.model.CreditOfferSuppressionCode
import com.openbank.lending.domain.model.OfferSurface
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ADR-0269 rules 1 and 2.
 *
 * These tests are written as falsification cases, not as a happy path with decoration: each one
 * flips exactly one input away from an otherwise-eligible borrower and asserts the SPECIFIC reason
 * code. A test that only asserted "not Allowed" would still pass if the evaluator suppressed
 * everything for the wrong reason, which is the failure mode that makes a gate unauditable.
 */
class CreditOfferEligibilityTest {

    private val now: Instant = Instant.parse("2026-08-21T10:00:00Z")

    /** A borrower with nothing wrong: every later test is this one with a single field changed. */
    private val healthy = BorrowerDistressSignals(
        hasArrears = false,
        hasNegativeBalance = false,
        hasEnforcementOrder = false,
        hasInsolvencyProceeding = false,
        inHardshipArrangement = false,
        lastAffordabilityFailureAt = null,
        bufferDays = 90,
        monthsObserved = 12,
        lastCreditContactAt = null,
        inputsChangedSinceLastContact = true,
        complete = true,
    )

    private fun decide(consent: Boolean = true, signals: BorrowerDistressSignals = healthy, at: Instant = now) =
        CreditOfferEligibility.evaluate(consent, signals, at)

    private fun codeOf(decision: CreditOfferDecision) = (decision as? CreditOfferDecision.Suppressed)?.code

    private fun assertSuppressed(decision: CreditOfferDecision, code: CreditOfferSuppressionCode) {
        assertThat(decision).isInstanceOf(CreditOfferDecision.Suppressed::class.java)
        assertThat((decision as CreditOfferDecision.Suppressed).code).isEqualTo(code)
    }

    @Test
    fun `an eligible borrower with consent is allowed`() {
        assertThat(decide()).isEqualTo(CreditOfferDecision.Allowed(CreditOfferPolicy.V1.version))
    }

    // ── Rule 1: consent ───────────────────────────────────────────────────────

    @Test
    fun `no consent means no offer even when every other signal is healthy`() {
        assertSuppressed(decide(consent = false), CreditOfferSuppressionCode.CONSENT_ABSENT)
    }

    // ── Fail-closed ───────────────────────────────────────────────────────────

    @Test
    fun `incomplete signals suppress rather than degrade open`() {
        assertSuppressed(
            decide(signals = healthy.copy(complete = false)),
            CreditOfferSuppressionCode.SIGNALS_UNAVAILABLE,
        )
    }

    @Test
    fun `an unknown buffer reads as below the floor, not as a healthy buffer`() {
        assertSuppressed(
            decide(signals = healthy.copy(bufferDays = null)),
            CreditOfferSuppressionCode.BUFFER_BELOW_FLOOR,
        )
    }

    // ── Rule 2: the distress floor, one flipped field at a time ───────────────

    @Test
    fun `each distress signal alone suppresses the offer with its own reason code`() {
        val cases = listOf(
            healthy.copy(hasInsolvencyProceeding = true) to CreditOfferSuppressionCode.INSOLVENCY,
            healthy.copy(hasEnforcementOrder = true) to CreditOfferSuppressionCode.ENFORCEMENT,
            healthy.copy(hasArrears = true) to CreditOfferSuppressionCode.ARREARS,
            healthy.copy(inHardshipArrangement = true) to CreditOfferSuppressionCode.HARDSHIP,
            healthy.copy(hasNegativeBalance = true) to CreditOfferSuppressionCode.NEGATIVE_BALANCE,
        )
        cases.forEach { (signals, expected) -> assertSuppressed(decide(signals = signals), expected) }
    }

    @Test
    fun `a recent affordability failure still binds inside the cooling window`() {
        val failedYesterday = healthy.copy(lastAffordabilityFailureAt = now.minus(1, ChronoUnit.DAYS))
        assertSuppressed(decide(signals = failedYesterday), CreditOfferSuppressionCode.AFFORDABILITY_COOLING)
    }

    @Test
    fun `an affordability failure older than the cooling window no longer binds`() {
        val longAgo = healthy.copy(
            lastAffordabilityFailureAt = now.minus(CreditOfferPolicy.V1.affordabilityCoolingDays, ChronoUnit.DAYS),
        )
        assertThat(decide(signals = longAgo)).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }

    @Test
    fun `a buffer one day under the floor is under the floor`() {
        val thin = healthy.copy(bufferDays = CreditOfferPolicy.V1.minimumBufferDays - 1)
        assertSuppressed(decide(signals = thin), CreditOfferSuppressionCode.BUFFER_BELOW_FLOOR)
    }

    @Test
    fun `a buffer exactly at the floor is allowed`() {
        val atFloor = healthy.copy(bufferDays = CreditOfferPolicy.V1.minimumBufferDays)
        assertThat(decide(signals = atFloor)).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }

    // ── Frequency and materiality ─────────────────────────────────────────────

    @Test
    fun `a contact inside the frequency window suppresses the next one`() {
        val contactedRecently = healthy.copy(lastCreditContactAt = now.minus(3, ChronoUnit.DAYS))
        assertSuppressed(decide(signals = contactedRecently), CreditOfferSuppressionCode.FREQUENCY_CAP)
    }

    @Test
    fun `outside the window a repeat contact still needs something new to say`() {
        val stale = healthy.copy(
            lastCreditContactAt = now.minus(CreditOfferPolicy.V1.contactFrequencyDays + 1, ChronoUnit.DAYS),
            inputsChangedSinceLastContact = false,
        )
        assertSuppressed(decide(signals = stale), CreditOfferSuppressionCode.NO_MATERIAL_CHANGE)
    }

    @Test
    fun `outside the window a changed input permits a second contact`() {
        val changed = healthy.copy(
            lastCreditContactAt = now.minus(CreditOfferPolicy.V1.contactFrequencyDays + 1, ChronoUnit.DAYS),
            inputsChangedSinceLastContact = true,
        )
        assertThat(decide(signals = changed)).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }

    // ── Precedence ────────────────────────────────────────────────────────────

    @Test
    fun `consent is evaluated before distress so a suppressed borrower without consent reads as CONSENT_ABSENT`() {
        assertSuppressed(
            decide(consent = false, signals = healthy.copy(hasArrears = true)),
            CreditOfferSuppressionCode.CONSENT_ABSENT,
        )
    }

    @Test
    fun `every decision carries the policy version that produced it`() {
        val custom =
            CreditOfferPolicy(
                version = 7,
                minimumBufferDays = 1,
                affordabilityCoolingDays = 1,
                contactFrequencyDays = 1,
                minimumMonthsObserved = 1,
            )
        val decision = CreditOfferEligibility.evaluate(true, healthy.copy(hasArrears = true), now, custom)
        assertThat(decision.policyVersion).isEqualTo(7)
    }

    // ── History depth (ADR-0269 #6215) ────────────────────────────────────────

    @Test
    fun `a push needs enough observed months to be evidence`() {
        val thin = healthy.copy(monthsObserved = CreditOfferPolicy.V1.minimumMonthsObserved - 1)
        assertSuppressed(decide(signals = thin), CreditOfferSuppressionCode.HISTORY_TOO_THIN)
    }

    @Test
    fun `an unknown history depth is not treated as a long one`() {
        assertSuppressed(
            decide(signals = healthy.copy(monthsObserved = null)),
            CreditOfferSuppressionCode.HISTORY_TOO_THIN,
        )
    }

    @Test
    fun `a thin history does not stop an answer the customer asked for`() {
        val thin = healthy.copy(monthsObserved = 0)
        val pull = CreditOfferEligibility.evaluate(true, thin, now, CreditOfferPolicy.V1, OfferSurface.PULL)
        assertThat(pull).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }

    @Test
    fun `HISTORY_TOO_THIN is distinct from SIGNALS_UNAVAILABLE — a new customer is not an outage`() {
        val newCustomer = decide(signals = healthy.copy(monthsObserved = 0))
        val brokenUpstream = decide(signals = healthy.copy(complete = false))
        assertThat(codeOf(newCustomer)).isNotEqualTo(codeOf(brokenUpstream))
    }
}
