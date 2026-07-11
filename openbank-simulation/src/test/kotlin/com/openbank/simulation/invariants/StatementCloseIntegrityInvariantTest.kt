// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.model.StatementCloseKey
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Isolation coverage for [MoneyPathInvariants.statementCloseIntegrity] (ADR-0035 §E / ADR-0078,
 * issue #667): a period is persisted if and only if the real `ReconciliationPolicy` reconciled —
 * proven both ways (mirrors [BillingFeeConservationInvariantTest]'s two-sided proof; the
 * seeded-scenario side lives in `StatementCloseScenarioTest` and the `DstSimulationTest` sweep).
 */
class StatementCloseIntegrityInvariantTest {

    private fun world(): World = World(SimulationContext(seed = 1L, FaultProfile.NONE), SimulationConfig())

    private fun key(): StatementCloseKey = StatementCloseKey(UUID.randomUUID(), "CZK", "attempt-1")

    @Test
    fun `holds trivially when no close has been attempted`() {
        assertThat(MoneyPathInvariants.statementCloseIntegrity.check(world())).isNull()
    }

    @Test
    fun `holds when a reconciled attempt was persisted`() {
        val w = world()
        val k = key()
        w.statementCloses.recordDecision(k, wasReconciled = true)
        w.statementCloses.recordPersisted(k, wasPersisted = true)

        assertThat(MoneyPathInvariants.statementCloseIntegrity.check(w)).isNull()
    }

    @Test
    fun `holds when a mismatched attempt was correctly refused`() {
        val w = world()
        val k = key()
        w.statementCloses.recordDecision(k, wasReconciled = false)
        w.statementCloses.recordPersisted(k, wasPersisted = false)

        assertThat(MoneyPathInvariants.statementCloseIntegrity.check(w)).isNull()
    }

    @Test
    fun `persisting a period despite a reconciliation mismatch is a violation`() {
        val w = world()
        val k = key()
        w.statementCloses.recordDecision(k, wasReconciled = false)
        // The defect this invariant exists to catch: a partial, self-inconsistent legal document
        // minted despite a failed reconciliation.
        w.statementCloses.recordPersisted(k, wasPersisted = true)

        val violation = MoneyPathInvariants.statementCloseIntegrity.check(w)

        assertThat(violation).isNotNull()
        assertThat(violation!!.invariant).isEqualTo("statement-close-integrity")
    }

    @Test
    fun `silently dropping a reconciled close is also a violation`() {
        val w = world()
        val k = key()
        w.statementCloses.recordDecision(k, wasReconciled = true)
        // Never persisted despite reconciling — the other side of the same defect class.
        w.statementCloses.recordPersisted(k, wasPersisted = false)

        assertThat(MoneyPathInvariants.statementCloseIntegrity.check(w)).isNotNull()
    }
}
