// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.usecase

import com.openbank.flakytest.domain.model.RunTrigger
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * The workflow id IS the deduplication. Temporal rejects a second start under an id already
 * running, so these equalities are what stop a pod restart or a second replica from launching a
 * duplicate sweep — which would spend the agent's daily LLM budget twice.
 */
class ScheduledWorkflowIdTest {

    private fun id(trigger: RunTrigger, iso: String) =
        FlakyTestHunterService.scheduledWorkflowId(trigger, Instant.parse(iso))

    private fun operatorId(idempotencyKey: String?, iso: String = "2026-09-02T00:05:00Z") =
        FlakyTestHunterService.operatorWorkflowId(idempotencyKey, Instant.parse(iso))

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
            "flaky-test-hunter-check-scheduled-2026-08-02",
            id(RunTrigger.SCHEDULED, "2026-08-02T23:59:59Z"),
        )
        assertEquals(
            "flaky-test-hunter-check-scheduled-2026-08-03",
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

    @Test
    fun `missing operator key preserves the current UTC-day workflow namespace`() {
        assertEquals(
            "flaky-test-hunter-check-operator_manual-2026-09-02",
            operatorId(null),
        )
    }

    @Test
    fun `same bounded operator key always resolves to the same existing workflow id`() {
        val key = "flaky-test-hunter-operator-manual-2026-09-02"

        assertEquals(operatorId(key), operatorId(key))
        assertEquals("flaky-test-hunter-check-operator_manual-2026-09-02", operatorId(key))
    }

    @Test
    fun `detached workflow options reject a second execution even after completion`() {
        val workflowId = operatorId("flaky-test-hunter-operator-manual-2026-09-02")

        val options = FlakyTestHunterService.detachedWorkflowOptions("test-queue", workflowId)

        assertEquals(workflowId, options.workflowId)
        assertEquals(
            WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE,
            options.workflowIdReusePolicy,
        )
    }

    @Test
    fun `previous UTC-day key is accepted for recovery across midnight`() {
        assertEquals(
            "flaky-test-hunter-check-operator_manual-2026-09-01",
            operatorId("flaky-test-hunter-operator-manual-2026-09-01"),
        )
    }

    @Test
    fun `malformed future and stale operator keys are rejected deterministically`() {
        listOf(
            "",
            "flaky-test-hunter-operator-manual-2026-02-30",
            "flaky-test-hunter-operator-manual-2026-09-03",
            "flaky-test-hunter-operator-manual-2026-08-31",
            "flaky-test-hunter-check-operator_manual-2026-09-02",
        ).forEach { key ->
            assertThrows<IllegalArgumentException> { operatorId(key) }
        }
    }
}
