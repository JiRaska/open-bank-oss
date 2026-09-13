// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.client

import com.openbank.lending.domain.model.BorrowerDistressSignals
import com.openbank.lending.domain.model.CourtRegisterSignalState
import com.openbank.lending.domain.model.CreditOfferDecision
import com.openbank.lending.domain.model.CreditOfferEligibility
import com.openbank.lending.domain.model.CreditOfferSuppressionCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The ADR-0269 blind spot (#6646) must stay *stated*, not silently encoded as a clean result.
 *
 * Every assertion here is written to go red under the specific regression it guards, and the
 * regression in each case is the cheap-looking one: collapsing "no register is configured" back
 * into the same value a real, consulted, empty register produces.
 */
class CourtRegisterSignalSourceTest {

    private val now: Instant = Instant.parse("2026-08-30T10:00:00Z")

    @Test
    fun `an unconfigured register is not reported as clear`() {
        val source = CourtRegisterSignalSource(SimpleMeterRegistry())

        // Goes red the moment either state is written back as CLEAR — which is what the adapter
        // said before #6646 and is indistinguishable from a genuine negative lookup.
        assertThat(source.insolvencyState()).isEqualTo(CourtRegisterSignalState.NOT_CONFIGURED)
        assertThat(source.enforcementState()).isEqualTo(CourtRegisterSignalState.NOT_CONFIGURED)
        assertThat(source.insolvencyState()).isNotEqualTo(CourtRegisterSignalState.CLEAR)
        assertThat(source.enforcementState()).isNotEqualTo(CourtRegisterSignalState.CLEAR)
    }

    @Test
    fun `no feed configured is exported as a gauge, so an empty finding set is readable`() {
        val meters = SimpleMeterRegistry()

        CourtRegisterSignalSource(meters)

        // The alert this repository was missing in the analogous push-adapter defect is
        // "the control has no input", and it is only expressible if the absence is a series.
        CourtRegisterSignalSource.UNCONFIGURED_SIGNALS.forEach { signal ->
            val gauge = meters.find(CourtRegisterSignalSource.FEED_CONFIGURED_GAUGE)
                .tag("signal", signal)
                .gauge()
            assertThat(gauge).describedAs("gauge for signal '%s'", signal).isNotNull
            assertThat(gauge!!.value()).isEqualTo(0.0)
        }
    }

    @Test
    fun `an unconfigured register never becomes a suppression reason it did not read`() {
        // NOT_CONFIGURED must not drift into meaning "marker present" either. The failure mode on
        // this side is quieter than it looks: it would suppress every offer forever, which reads
        // exactly like the feature being switched off.
        assertThat(CourtRegisterSignalState.NOT_CONFIGURED.suppresses).isFalse()
        assertThat(CourtRegisterSignalState.CLEAR.suppresses).isFalse()
        assertThat(CourtRegisterSignalState.MARKER_PRESENT.suppresses).isTrue()
    }

    @Test
    fun `the three register states are three distinct decisions, not two`() {
        val configuredAndClear = CreditOfferEligibility.evaluate(true, signals(CourtRegisterSignalState.CLEAR), now)
        val markerOnFile = CreditOfferEligibility.evaluate(true, signals(CourtRegisterSignalState.MARKER_PRESENT), now)
        val noFeed = CreditOfferEligibility.evaluate(true, signals(CourtRegisterSignalState.NOT_CONFIGURED), now)

        assertThat(configuredAndClear).isInstanceOf(CreditOfferDecision.Allowed::class.java)
        assertThat(markerOnFile)
            .isEqualTo(
                CreditOfferDecision.Suppressed(markerOnFile.policyVersion, CreditOfferSuppressionCode.INSOLVENCY),
            )

        // The load-bearing one. An INSOLVENCY suppression must be attributable to a register that
        // was actually consulted, so the unconfigured deployment cannot produce that code — and
        // conversely, its absence from production must never be read as evidence about customers.
        assertThat(noFeed).isInstanceOf(CreditOfferDecision.Allowed::class.java)
    }

    private fun signals(insolvency: CourtRegisterSignalState) = BorrowerDistressSignals(
        hasArrears = false,
        hasNegativeBalance = false,
        enforcementSignal = CourtRegisterSignalState.NOT_CONFIGURED,
        insolvencySignal = insolvency,
        inHardshipArrangement = false,
        lastAffordabilityFailureAt = null,
        bufferDays = 90,
        monthsObserved = 12,
        lastCreditContactAt = null,
        inputsChangedSinceLastContact = true,
        complete = true,
    )
}
