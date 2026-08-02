// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.usecase

import com.openbank.govaudit.domain.model.RunTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The workflow id IS the deduplication. Temporal rejects a second start under an id already
 * running, so these equalities are what stop a pod restart or a second replica from launching a
 * duplicate sweep — which would spend the agent's daily LLM budget twice.
 */
class ScheduledWorkflowIdTest {

    private fun id(trigger: RunTrigger, iso: String) =
        GovernanceAuditorService.scheduledWorkflowId(trigger, Instant.parse(iso))

    @Test
    fun `two fires on the same UTC day produce the same id`() {
        assertEquals(
            id(RunTrigger.SCHEDULED, "2026-08-02T03:00:00Z"),
            id(RunTrigger.SCHEDULED, "2026-08-02T21:40:11Z"),
        )
    }

    @Test
    fun `consecutive days produce different ids`() {
        assertNotEquals(
            id(RunTrigger.SCHEDULED, "2026-08-02T03:00:00Z"),
            id(RunTrigger.SCHEDULED, "2026-08-03T03:00:00Z"),
        )
    }

    @Test
    fun `the day boundary is UTC, not the JVM default zone`() {
        // 23:30 Prague on the 2nd is 21:30 UTC the same day; 00:30 Prague on the 3rd is 22:30 UTC
        // on the 2nd. A local-zone truncation would split the first pair and merge the second, so
        // the schedule would double-run on some days and skip others depending on where the pod runs.
        assertEquals(
            "governance-audit-scheduled-2026-08-02",
            id(RunTrigger.SCHEDULED, "2026-08-02T23:59:59Z"),
        )
        assertEquals(
            "governance-audit-scheduled-2026-08-03",
            id(RunTrigger.SCHEDULED, "2026-08-03T00:00:00Z"),
        )
    }

    @Test
    fun `an operator run is never blocked by the day's scheduled run`() {
        assertNotEquals(
            id(RunTrigger.SCHEDULED, "2026-08-02T03:00:00Z"),
            id(RunTrigger.OPERATOR_MANUAL, "2026-08-02T03:00:00Z"),
        )
    }
}
