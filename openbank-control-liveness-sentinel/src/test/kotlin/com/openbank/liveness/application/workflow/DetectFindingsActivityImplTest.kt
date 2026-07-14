// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.liveness.application.port.out.GovernanceReadPort
import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DetectFindingsActivityImplTest {

    private val config = mockk<LivenessSentinelConfig> {
        every { staleHeartbeatMultiplier() } returns 2.0
        every { warnHeartbeatMultiplier() } returns 1.5
        every { consecutiveDriftThreshold() } returns 3
    }
    private val governanceRead = mockk<GovernanceReadPort>()
    private val activity = DetectFindingsActivityImpl(config, governanceRead)

    @Test
    fun `stale heartbeat below warn threshold produces no finding`() {
        // job expects a heartbeat every 100s; 120s old is 1.2x -- below the 1.5x warn line.
        val findings = activity.detect(
            ControlMechanism.M3_WORKFLOW_WATCHDOG,
            mapOf(
                "balance-reconciliation|100" to 120.0,
            ),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `stale heartbeat between warn and critical threshold produces a WARNING`() {
        // 1.5x-2x expected interval -> WARNING, not yet CRITICAL.
        val findings = activity.detect(
            ControlMechanism.M3_WORKFLOW_WATCHDOG,
            mapOf(
                "balance-reconciliation|100" to 170.0,
            ),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.WARNING)
        assertThat(findings.single().affectedControl).isEqualTo("balance-reconciliation")
    }

    @Test
    fun `stale heartbeat at or past critical threshold produces a CRITICAL finding`() {
        // 2x expected interval -- matches ADR-0160 mechanism 3's own Alertmanager paging line.
        val findings = activity.detect(
            ControlMechanism.M3_WORKFLOW_WATCHDOG,
            mapOf(
                "balance-reconciliation|100" to 200.0,
            ),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `heartbeat signal missing the composite interval suffix is skipped, not crashed on`() {
        val findings = activity.detect(ControlMechanism.M3_WORKFLOW_WATCHDOG, mapOf("no-interval-here" to 999.0))
        assertThat(findings).isEmpty()
    }

    @Test
    fun `sustained drift below threshold produces no finding`() {
        val findings = activity.detect(
            ControlMechanism.M4_RECONCILIATION_DRIFT_SLA,
            mapOf(
                "balance-reconciliation" to 2.0,
            ),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `sustained drift at or past threshold produces a CRITICAL finding`() {
        val findings = activity.detect(
            ControlMechanism.M4_RECONCILIATION_DRIFT_SLA,
            mapOf(
                "balance-reconciliation" to 3.0,
            ),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }
}
