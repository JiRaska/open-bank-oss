// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.workflow

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.devops.domain.model.CiGateObservation
import com.openbank.devops.domain.model.CiGateProfileEvidence
import com.openbank.devops.domain.model.CiGateProfileProvenance
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RunTrigger
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class DevOpsGateProfileWorkflowTest {
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun environment(): TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(
            WorkflowClientOptions.newBuilder().setDataConverter(
                DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
                    JacksonJsonPayloadConverter(
                        JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule(),
                    ),
                ),
            ).build(),
        ).build(),
    )

    private fun registerActivities(
        env: TestWorkflowEnvironment,
        legacy: Boolean,
        profile: CiGateProfileEvidence,
        calls: MutableList<String>,
        failProfile: Boolean = false,
        profileOnly: Boolean = false,
        emitAdvisory: Boolean = false,
        failAdvisory: Boolean = false,
    ) {
        val worker = env.newWorker("devops-profile-test")
        worker.registerWorkflowImplementationTypes(
            when {
                legacy -> LegacyDevOpsAnalysisWorkflowImpl::class.java
                profileOnly -> ProfileOnlyDevOpsAnalysisWorkflowImpl::class.java
                else -> DevOpsAnalysisWorkflowImpl::class.java
            },
        )
        val collect = object : CollectSignalsActivity {
            override fun collectCiPipelineSignals(): Map<String, Double> = emptyMap()
            override fun collectDoraSignals(): Map<String, Double> = emptyMap()
            override fun collectRunnerCapacitySignals(): Map<String, Double> = emptyMap()
            override fun collectDeployHealthSignals(): Map<String, Double> = emptyMap()
            override fun collectSsdlcSignals(): Map<String, Double> = emptyMap()
            override fun collectIncidentRecurrenceSignals(): Map<String, Double> = emptyMap()
        }
        val finding = DevOpsFinding(
            "fixture",
            DetectorId.D1_CI_PIPELINE_HEALTH,
            FindingSeverity.CRITICAL,
            at,
            "fixture finding",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "fixture resource",
        )
        val detect = object : DetectFindingsActivity {
            override fun detect(detectorId: DetectorId, signals: Map<String, Double>): List<DevOpsFinding> {
                calls.add(detectorId.name)
                return if (detectorId == DetectorId.D1_CI_PIPELINE_HEALTH) listOf(finding) else emptyList()
            }
        }
        val diagnosis = object : DiagnoseAndProposeActivity {
            override fun diagnose(finding: DevOpsFinding, contextSignals: Map<String, Double>): DevOpsFinding {
                calls.add("diagnose")
                return finding.copy(status = FindingStatus.DIAGNOSED)
            }

            override fun propose(finding: DevOpsFinding): DevOpsFinding {
                calls.add("propose")
                return finding.copy(status = FindingStatus.PROPOSED)
            }
        }
        val activity = object : CiGateProfileActivity {
            override fun collectGateProfile(): CiGateProfileEvidence {
                calls.add("profile")
                if (failProfile) throw ApplicationFailure.newFailure("fixture unavailable", "Fixture")
                return profile
            }
        }
        val advisory = object : CpuOptimizationAdvisoryActivity {
            override fun assess(profile: CiGateProfileEvidence): DevOpsFinding? {
                calls.add("advisory")
                if (failAdvisory) throw ApplicationFailure.newNonRetryableFailure("fixture unavailable", "Fixture")
                return if (emitAdvisory) {
                    finding.copy(
                        id = "cpu-advisory",
                        severity = FindingSeverity.WARNING,
                        status = FindingStatus.DIAGNOSED,
                    )
                } else {
                    null
                }
            }
        }
        worker.registerActivitiesImplementations(collect, detect, diagnosis, activity, advisory)
        env.start()
    }

    private fun stub(env: TestWorkflowEnvironment): DevOpsAnalysisWorkflow = env.workflowClient.newWorkflowStub(
        DevOpsAnalysisWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue("devops-profile-test").build(),
    )

    @Test
    fun `new workflow preserves collected evidence and leaves existing findings flow intact`() {
        val evidence = CiGateProfileEvidence(
            "example/repo",
            at,
            DeliveryEvidenceState.PARTIAL,
            "SINGLE_ARTIFACT",
            CiGateProfileProvenance("a".repeat(40), 11, 2, 12, "gate-results-lint-a2", "sha256:" + "b".repeat(64), at),
            listOf(
                CiGateObservation(
                    "lint", "lint", "enforced", "ok", null, 4.0, 3.0, 10.0, 1.0,
                    true, true, false, 42, 1, false,
                ),
            ),
        )
        environment().use { env ->
            val calls = mutableListOf<String>()
            registerActivities(env, false, evidence, calls)
            val workflow = WorkflowStub.fromTyped(stub(env))
            val execution = workflow.start(RunTrigger.SCHEDULED)
            val result = workflow.getResult(DevOpsRunReport::class.java)
            assertThat(result.ciGateProfile).isEqualTo(evidence)
            val history = env.getWorkflowExecutionHistory(execution)
            assertThat(history.events.any { it.hasMarkerRecordedEventAttributes() }).isTrue()
            environment().use { replay ->
                WorkflowReplayer.replayWorkflowExecution(history, replay, DevOpsAnalysisWorkflowImpl::class.java)
            }
            assertThat(result.findingsProposed).isEqualTo(1)
            assertThat(calls).containsExactly(
                *DetectorId.entries.map { it.name }.toTypedArray(),
                "profile",
                "diagnose",
                "propose",
                "advisory",
            )
        }
    }

    @Test
    fun `unavailable evidence remains explicit in the completed workflow report`() {
        val evidence = CiGateProfileEvidence("example/repo", at, DeliveryEvidenceState.UNAVAILABLE, "API_BUDGET")
        environment().use { env ->
            val calls = mutableListOf<String>()
            registerActivities(env, false, evidence, calls)
            assertThat(stub(env).runAnalysis(RunTrigger.SCHEDULED).ciGateProfile).isEqualTo(evidence)
            assertThat(calls.count { it == "profile" }).isEqualTo(1)
        }
    }

    @Test
    fun `failed profile activity does not discard existing findings or trigger a retry`() {
        environment().use { env ->
            val calls = mutableListOf<String>()
            registerActivities(
                env,
                false,
                CiGateProfileEvidence("example/repo", at, DeliveryEvidenceState.UNAVAILABLE),
                calls,
                failProfile = true,
            )
            val report = stub(env).runAnalysis(RunTrigger.SCHEDULED)
            assertThat(report.ciGateProfile).isNull()
            assertThat(report.findingsProposed).isEqualTo(1)
            assertThat(calls.count { it == "profile" }).isEqualTo(1)
            assertThat(calls.takeLast(2)).containsExactly("diagnose", "propose")
        }
    }

    @Test
    fun `legacy history without version markers replays against the current workflow`() {
        environment().use { env ->
            val calls = mutableListOf<String>()
            registerActivities(
                env,
                true,
                CiGateProfileEvidence("example/repo", at, DeliveryEvidenceState.UNAVAILABLE),
                calls,
            )
            val workflow = stub(env)
            val untyped = WorkflowStub.fromTyped(workflow)
            val execution = untyped.start(RunTrigger.SCHEDULED)
            assertThat(untyped.getResult(DevOpsRunReport::class.java).ciGateProfile).isNull()
            assertThat(calls).containsExactly(*DetectorId.entries.map { it.name }.toTypedArray(), "diagnose", "propose")
            val history = env.getWorkflowExecutionHistory(execution)
            assertThat(history.events.none { it.hasMarkerRecordedEventAttributes() }).isTrue()
            environment().use { replay ->
                WorkflowReplayer.replayWorkflowExecution(history, replay, DevOpsAnalysisWorkflowImpl::class.java)
            }
        }
    }

    @Test
    fun `profile-only history replays with the new advisory activity version disabled`() {
        environment().use { env ->
            val calls = mutableListOf<String>()
            val profile = CiGateProfileEvidence("example/repo", at, DeliveryEvidenceState.UNAVAILABLE)
            registerActivities(env, legacy = false, profile = profile, calls = calls, profileOnly = true)
            val workflow = stub(env)
            workflow.runAnalysis(RunTrigger.OPERATOR_MANUAL)
            val history = env.workflowClient.fetchHistory(WorkflowStub.fromTyped(workflow).execution.workflowId)
            environment().use { replay ->
                WorkflowReplayer.replayWorkflowExecution(history, replay, DevOpsAnalysisWorkflowImpl::class.java)
            }
            assertThat(calls).doesNotContain("advisory")
        }
    }

    @Test
    fun `advisory enters report without another diagnosis or proposal`() {
        environment().use { env ->
            val calls = mutableListOf<String>()
            val profile = CiGateProfileEvidence("example/repo", at, DeliveryEvidenceState.PARTIAL)
            registerActivities(env, false, profile, calls, emitAdvisory = true)
            val result = stub(env).runAnalysis(RunTrigger.OPERATOR_MANUAL)
            assertThat(result.findingsDetected.map { it.id }).containsExactly("fixture", "cpu-advisory")
            assertThat(result.findingsProposed).isEqualTo(1)
            assertThat(calls.count { it == "diagnose" }).isEqualTo(1)
            assertThat(calls.count { it == "propose" }).isEqualTo(1)
        }
    }

    @Test
    fun `advisory storage failure preserves the existing findings and profile`() {
        environment().use { env ->
            val calls = mutableListOf<String>()
            val profile = CiGateProfileEvidence("example/repo", at, DeliveryEvidenceState.PARTIAL)
            registerActivities(env, false, profile, calls, failAdvisory = true)
            val result = stub(env).runAnalysis(RunTrigger.OPERATOR_MANUAL)
            assertThat(result.findingsDetected.map { it.id }).containsExactly("fixture")
            assertThat(result.ciGateProfile).isEqualTo(profile)
            assertThat(result.findingsProposed).isEqualTo(1)
        }
    }
}
