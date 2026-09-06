// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.flakytest.domain.model.TestScanSnapshot
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The orchestration decision in [FlakyTestHunterWorkflowImpl]: every finding is diagnosed, but only
 * a CRITICAL one is proposed. A regression that proposed every finding — or none — would keep the
 * report shape intact and only show up here.
 *
 * Pure in-process Temporal test environment: no external Temporal server and no container.
 */
class FlakyTestHunterWorkflowImplTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var collect: RecordingCollectActivity
    private lateinit var detect: RecordingDetectActivity
    private lateinit var diagnosePropose: RecordingDiagnoseActivity

    private class RecordingCollectActivity(private val snapshot: TestScanSnapshot) : CollectTestScanActivity {
        override fun collect(): TestScanSnapshot = snapshot
    }

    private class RecordingDetectActivity(private val findings: List<FlakyTestFinding>) : DetectDriftActivity {
        var seenSnapshot: TestScanSnapshot? = null
        override fun detect(snapshot: TestScanSnapshot): List<FlakyTestFinding> {
            seenSnapshot = snapshot
            return findings
        }
    }

    private class RecordingDiagnoseActivity : DiagnoseAndProposeActivity {
        val diagnosed = mutableListOf<String>()
        val proposed = mutableListOf<String>()

        override fun diagnose(finding: FlakyTestFinding, contextMetrics: Map<String, Double>): FlakyTestFinding {
            diagnosed += finding.id
            return finding.copy(rootCause = "diagnosed", status = FindingStatus.DIAGNOSED)
        }

        override fun propose(finding: FlakyTestFinding): FlakyTestFinding {
            proposed += finding.id
            return finding.copy(status = FindingStatus.PROPOSED, proposalUrl = "https://example.invalid/pr/1")
        }
    }

    private fun finding(id: String, severity: FindingSeverity) = FlakyTestFinding(
        id = id,
        checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
        severity = severity,
        detectedAt = Instant.parse("2026-08-22T10:00:00Z"),
        title = "silently dropped test $id",
        component = "openbank-ledger-service",
        filePath = "src/test/kotlin/Foo.kt",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
    )

    private fun start(snapshot: TestScanSnapshot, findings: List<FlakyTestFinding>) {
        collect = RecordingCollectActivity(snapshot)
        detect = RecordingDetectActivity(findings)
        diagnosePropose = RecordingDiagnoseActivity()
        worker.registerActivitiesImplementations(collect, detect, diagnosePropose)
        env.start()
    }

    private fun runCheck(trigger: RunTrigger = RunTrigger.OPERATOR_MANUAL) = env.workflowClient
        .newWorkflowStub(
            FlakyTestHunterWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
        )
        .runCheck(trigger)

    private fun emptySnapshot(testFilesScanned: Int) = TestScanSnapshot(
        testFilesScanned = testFilesScanned,
        runBlockingViolations = emptyList(),
        pactGatedClasses = emptyList(),
        pactProviderDeclarations = emptyList(),
        testCountSamples = emptyList(),
    )

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(FlakyTestHunterWorkflowImpl::class.java)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `only a critical finding is proposed and the report counts exactly those`() {
        start(
            emptySnapshot(1234),
            listOf(
                finding("a", FindingSeverity.CRITICAL),
                finding("b", FindingSeverity.WARNING),
                finding("c", FindingSeverity.CRITICAL),
            ),
        )

        val report = runCheck()

        assertThat(diagnosePropose.diagnosed).containsExactlyInAnyOrder("a", "b", "c")
        assertThat(diagnosePropose.proposed).containsExactlyInAnyOrder("a", "c")
        assertThat(report.findingsProposed).isEqualTo(2)
        assertThat(report.findingsDetected).hasSize(3)
        assertThat(report.findingsDetected.filter { it.status == FindingStatus.PROPOSED }.map { it.id })
            .containsExactlyInAnyOrder("a", "c")
        assertThat(report.findingsDetected.single { it.id == "b" }.status)
            .isEqualTo(FindingStatus.DIAGNOSED)
    }

    @Test
    fun `the report carries the collected scan size, the trigger and a bounded time window`() {
        start(emptySnapshot(77), emptyList())

        val report = runCheck(RunTrigger.SCHEDULED)

        assertThat(report.testFilesScanned).isEqualTo(77)
        assertThat(report.trigger).isEqualTo(RunTrigger.SCHEDULED)
        assertThat(report.findingsDetected).isEmpty()
        assertThat(report.findingsProposed).isZero()
        assertThat(report.runId).isNotBlank()
        assertThat(report.completedAt).isAfterOrEqualTo(report.startedAt)
    }

    @Test
    fun `the detect activity receives the snapshot the collect activity produced`() {
        val snapshot = emptySnapshot(9)
        start(snapshot, emptyList())

        runCheck()

        assertThat(detect.seenSnapshot).isEqualTo(snapshot)
    }

    @Test
    fun `two runs of the same workflow get distinct run ids`() {
        start(emptySnapshot(1), emptyList())

        assertThat(runCheck().runId).isNotEqualTo(runCheck().runId)
    }

    private companion object {
        const val TASK_QUEUE = "test-flaky-test-hunter"
    }
}
