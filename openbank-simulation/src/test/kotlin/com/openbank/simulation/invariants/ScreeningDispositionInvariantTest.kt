// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Isolation coverage for [MoneyPathInvariants.screeningDispositionIntegrity] (ADR-0032 §B): the
 * real `ScreeningPolicy`'s verdict and the payment path's disposition must never disagree —
 * proven both ways (mirrors [StatementCloseIntegrityInvariantTest]'s two-sided proof; the
 * seeded-scenario side lives in [FxScreeningScenarioTest] and the `DstSimulationTest` sweep).
 */
class ScreeningDispositionInvariantTest {

    private fun world(): World = World(SimulationContext(seed = 1L, FaultProfile.NONE), SimulationConfig())

    private fun conversion(): UUID = UUID.randomUUID()

    @Test
    fun `holds trivially when no conversion has been screened`() {
        assertThat(MoneyPathInvariants.screeningDispositionIntegrity.check(world())).isNull()
    }

    @Test
    fun `holds when a CLEAR conversion executed and was never refused`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.CLEAR)
        w.screening.markExecuted(id)

        assertThat(MoneyPathInvariants.screeningDispositionIntegrity.check(w)).isNull()
    }

    @Test
    fun `holds when a BLOCK conversion was refused and never executed`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.BLOCK)
        w.screening.markRefused(id)

        assertThat(MoneyPathInvariants.screeningDispositionIntegrity.check(w)).isNull()
    }

    @Test
    fun `holds when a REVIEW alert was resolved once as a false positive and then executed`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.openReview(id)
        w.screening.resolveReview(id, escalated = false)
        w.screening.markExecuted(id)

        assertThat(MoneyPathInvariants.screeningDispositionIntegrity.check(w)).isNull()
    }

    @Test
    fun `holds when a REVIEW alert was resolved once as a confirmed hit and refused`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.openReview(id)
        w.screening.resolveReview(id, escalated = true)
        w.screening.markRefused(id)

        assertThat(MoneyPathInvariants.screeningDispositionIntegrity.check(w)).isNull()
    }

    @Test
    fun `a REVIEW resolved without ever opening an alert is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.resolveReview(id, escalated = false)
        w.screening.markExecuted(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("without ever opening")
    }

    @Test
    fun `a reopened REVIEW remains unsettled despite a prior resolution`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.openReview(id)
        w.screening.resolveReview(id, escalated = false)
        w.screening.openReview(id)
        w.screening.markExecuted(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("not settled", "open=true")
    }

    @Test
    fun `executing a BLOCK conversion is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.BLOCK)
        // The defect this invariant exists to catch: a conversion screened to BLOCK that the
        // payment path executed anyway (e.g. the verdict was dropped between screening and posting).
        w.screening.markExecuted(id)
        w.screening.markRefused(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.invariant).isEqualTo("screening-disposition-integrity")
    }

    @Test
    fun `a BLOCK conversion that was neither refused nor executed is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.BLOCK)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("BLOCK never refused")
    }

    @Test
    fun `a silently dropped REVIEW alert is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        // The alert never reached a human — no resolution recorded at all.
        w.screening.openReview(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("silently dropped")
    }

    @Test
    fun `a REVIEW alert resolved twice is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.openReview(id)
        w.screening.resolveReview(id, escalated = false)
        // Double resolution: two humans acting on the same alert, or an idempotency gap —
        // either way the disposition is no longer trustworthy.
        w.screening.resolveReview(id, escalated = false)
        w.screening.markExecuted(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("resolved 2 times")
    }

    @Test
    fun `an escalated hit that still executed is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.openReview(id)
        w.screening.resolveReview(id, escalated = true)
        w.screening.markExecuted(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("escalated hit")
    }

    @Test
    fun `a false positive that was released but never executed is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.openReview(id)
        w.screening.resolveReview(id, escalated = false)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("never executed")
    }

    @Test
    fun `a released REVIEW that was both refused and executed is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.REVIEW)
        w.screening.openReview(id)
        w.screening.resolveReview(id, escalated = false)
        w.screening.markRefused(id)
        w.screening.markExecuted(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("false positive released but refused")
    }

    @Test
    fun `refusing a CLEAR conversion is a violation`() {
        val w = world()
        val id = conversion()
        w.screening.recordDecision(id, ScreeningDecision.CLEAR)
        w.screening.markRefused(id)
        w.screening.markExecuted(id)

        val violation = MoneyPathInvariants.screeningDispositionIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.detail).contains("CLEAR but refused")
    }
}
