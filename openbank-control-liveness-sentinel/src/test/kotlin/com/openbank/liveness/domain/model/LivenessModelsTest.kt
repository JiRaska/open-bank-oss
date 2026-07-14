// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class LivenessModelsTest {

    @Test
    fun `LivenessFinding defaults to OPEN status`() {
        val finding = LivenessFinding(
            id = "test-id",
            mechanism = ControlMechanism.M3_WORKFLOW_WATCHDOG,
            severity = FindingSeverity.WARNING,
            detectedAt = Instant.now(),
            title = "Stale heartbeat",
            affectedControl = "balance-reconciliation-job",
            rawMetricValue = BigDecimal.valueOf(7200.0),
            threshold = BigDecimal.valueOf(3600.0),
        )
        assertThat(finding.status).isEqualTo(FindingStatus.OPEN)
        assertThat(finding.rootCause).isNull()
        assertThat(finding.proposalPrUrl).isNull()
    }

    @Test
    fun `LivenessRunReport counts proposed findings`() {
        val now = Instant.now()
        val finding = LivenessFinding(
            id = "f1",
            mechanism = ControlMechanism.M4_RECONCILIATION_DRIFT_SLA,
            severity = FindingSeverity.CRITICAL,
            detectedAt = now,
            title = "Sustained drift",
            affectedControl = "balance-reconciliation",
            rawMetricValue = BigDecimal.valueOf(4.0),
            threshold = BigDecimal.valueOf(3.0),
            status = FindingStatus.PROPOSED,
        )
        val report = LivenessRunReport(
            runId = "run-1",
            startedAt = now,
            completedAt = now,
            findingsDetected = listOf(finding),
            findingsProposed = 1,
            tokensUsed = 0,
            trigger = RunTrigger.SCHEDULED,
        )
        assertThat(report.findingsProposed).isEqualTo(1)
        assertThat(report.findingsDetected).hasSize(1)
    }

    @Test
    fun `ControlMechanism enum covers all four ADR-0160 mechanisms`() {
        assertThat(ControlMechanism.values()).containsExactlyInAnyOrder(
            ControlMechanism.M1_EVENT_CONSUMER_LIVENESS,
            ControlMechanism.M2_LINEAGE_VS_CODE,
            ControlMechanism.M3_WORKFLOW_WATCHDOG,
            ControlMechanism.M4_RECONCILIATION_DRIFT_SLA,
        )
    }
}
