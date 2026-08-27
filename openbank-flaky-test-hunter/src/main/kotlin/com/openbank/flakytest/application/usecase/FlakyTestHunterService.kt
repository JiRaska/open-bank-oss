// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.usecase

import com.openbank.flakytest.application.port.incoming.AnalyzeTestIntelligenceUseCase
import com.openbank.flakytest.application.port.incoming.GetFindingsUseCase
import com.openbank.flakytest.application.port.incoming.RunFlakyTestCheckUseCase
import com.openbank.flakytest.application.port.out.FindingRepository
import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.application.workflow.FlakyTestHunterWorkflow
import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.FlakyTestReport
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.flakytest.domain.model.TestIntelligenceAnalysisRequest
import com.openbank.flakytest.domain.model.TestIntelligenceComponentInput
import com.openbank.libs.temporal.TemporalConfig
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

@ApplicationScoped
class FlakyTestHunterService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
    private val llmDiagnosis: LlmDiagnosisPort,
    private val clock: Clock,
    private val tracer: Tracer,
) : RunFlakyTestCheckUseCase,
    GetFindingsUseCase,
    AnalyzeTestIntelligenceUseCase {

    private val log = Logger.getLogger(FlakyTestHunterService::class.java)

    override suspend fun run(trigger: RunTrigger): FlakyTestReport {
        log.infof("Starting flaky-test-hunter check workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("flaky-test-hunter-check-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(FlakyTestHunterWorkflow::class.java, options)
        return workflow.runCheck(trigger)
    }

    override suspend fun startDetached(trigger: RunTrigger): String {
        val workflowId = scheduledWorkflowId(trigger, Instant.now(clock))
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId(workflowId)
            .build()
        val stub = workflowClient.newWorkflowStub(FlakyTestHunterWorkflow::class.java, options)
        try {
            WorkflowClient.start({ stub.runCheck(trigger) })
            log.infof("Started flaky-test-hunter sweep workflow %s (trigger=%s)", workflowId, trigger)
        } catch (duplicate: WorkflowExecutionAlreadyStarted) {
            // Not an error — the dedupe working. Two pods, or one restarted after the cron fired,
            // compute the same id and Temporal admits only the first.
            log.infof("Sweep workflow %s already running, not starting a second: %s", workflowId, duplicate.message)
        }
        return workflowId
    }

    /**
     * A read of the evidence-backed operator queue is a control-plane operation, not merely an
     * HTTP transport event.  Emit a bounded semantic span so an executable trace contract can
     * prove the operation reached its repository without putting findings, IDs or prompt data in
     * telemetry.
     */
    override suspend fun getActive(): List<FlakyTestFinding> {
        val span = tracer.spanBuilder("flaky-test-hunter.findings.read")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan()
        return runCatching {
            findingRepository.findActive().also { findings ->
                span.setAttribute("openbank.flaky.findings.count", findings.size.toLong())
            }
        }.onFailure { failure ->
            span.recordException(failure)
            span.setStatus(StatusCode.ERROR)
        }.also {
            span.end()
        }.getOrThrow()
    }

    override suspend fun getById(id: String): FlakyTestFinding? = findingRepository.findById(id)

    override suspend fun analyze(request: TestIntelligenceAnalysisRequest): List<FlakyTestFinding> {
        validateEvidenceRequest(request)
        return request.components.flatMap { component -> detectEvidenceFindings(request.snapshotId, component) }
            .map { finding -> diagnoseEvidenceFinding(finding, request.collectedAt) }
    }

    private fun detectEvidenceFindings(snapshotId: String, component: TestIntelligenceComponentInput) = buildList {
        val severity = if (component.moneyPath) FindingSeverity.CRITICAL else FindingSeverity.WARNING
        if (component.evidence.none {
                it.kind in EXECUTION_KINDS
            }
        ) {
            add(
                evidenceFinding(
                    snapshotId,
                    component.component,
                    FlakyTestCheckType.MISSING_EXECUTION_EVIDENCE,
                    severity,
                    "${component.component} has no execution evidence",
                ),
            )
        }
        addAll(detectCurrentAndHistoricalFailures(snapshotId, component, severity))
        if (component.evidence.any {
                it.state == "stale"
            }
        ) {
            add(
                evidenceFinding(
                    snapshotId,
                    component.component,
                    FlakyTestCheckType.STALE_TEST_EVIDENCE,
                    FindingSeverity.WARNING,
                    "${component.component} has stale test evidence",
                ),
            )
        }
        if (component.declaredInfrastructure.isNotEmpty() &&
            component.observedInfrastructureStarts == 0
        ) {
            add(
                evidenceFinding(
                    snapshotId,
                    component.component,
                    FlakyTestCheckType.UNPROVEN_TEST_INFRASTRUCTURE,
                    severity,
                    "${component.component} has no observed test infrastructure start",
                ),
            )
        }
        if (component.observedInfrastructureStarts > component.observedInfrastructureStops) {
            add(
                evidenceFinding(
                    snapshotId,
                    component.component,
                    FlakyTestCheckType.UNTERMINATED_TEST_INFRASTRUCTURE,
                    severity,
                    "${component.component} emitted ${component.observedInfrastructureStarts} test-infrastructure start event(s) but only ${component.observedInfrastructureStops} stop event(s)",
                    BigDecimal(component.observedInfrastructureStarts - component.observedInfrastructureStops),
                ),
            )
        }
    }

    private fun detectCurrentAndHistoricalFailures(
        snapshotId: String,
        component: TestIntelligenceComponentInput,
        severity: FindingSeverity,
    ) = buildList {
        if (component.evidence.any { it.state == "failed" }) {
            add(
                evidenceFinding(
                    snapshotId,
                    component.component,
                    FlakyTestCheckType.FAILED_TEST_EVIDENCE,
                    severity,
                    "${component.component} has failed test evidence",
                ),
            )
        }
        if (component.flakyTests > 0) {
            add(
                evidenceFinding(
                    snapshotId,
                    component.component,
                    FlakyTestCheckType.OBSERVED_FLAKY_TESTS,
                    severity,
                    "${component.component} has ${component.flakyTests} flaky test(s), " +
                        "${component.sameCommitTransitions} same-commit transition(s), " +
                        "and ${component.wastedDurationMs} ms of measured wasted duration",
                    BigDecimal(component.flakyTests),
                ),
            )
        }
        if (component.failingTests > 0) {
            add(
                evidenceFinding(
                    snapshotId,
                    component.component,
                    FlakyTestCheckType.OBSERVED_FAILING_TESTS,
                    severity,
                    "${component.component} has ${component.failingTests} test(s) failing in retained history",
                    BigDecimal(component.failingTests),
                ),
            )
        }
    }

    private suspend fun diagnoseEvidenceFinding(finding: FlakyTestFinding, collectedAt: Instant): FlakyTestFinding =
        findingRepository.findById(finding.id) ?: findingRepository.save(
            finding.copy(
                rootCause = llmDiagnosis.diagnose(
                    finding,
                    mapOf(
                        "snapshot_age_seconds" to
                            java.time.Duration.between(
                                collectedAt,
                                Instant.now(clock),
                            ).seconds.coerceAtLeast(0).toDouble(),
                    ),
                ),
                status = FindingStatus.DIAGNOSED,
                diagnosedAt = Instant.now(clock),
            ),
        )

    private fun evidenceFinding(
        snapshotId: String,
        component: String,
        type: FlakyTestCheckType,
        severity: FindingSeverity,
        title: String,
        rawMetricValue: BigDecimal = BigDecimal.ONE,
    ): FlakyTestFinding = FlakyTestFinding(
        UUID.nameUUIDFromBytes(
            "$snapshotId:$component:$type".toByteArray(StandardCharsets.UTF_8),
        ).toString(),
        type, severity,
        Instant.now(
            clock,
        ),
        title, component, "test-intelligence:$snapshotId", rawMetricValue, BigDecimal.ZERO,
    )

    private fun validateEvidenceRequest(request: TestIntelligenceAnalysisRequest) {
        require(request.snapshotId.isNotBlank() && request.snapshotId.length <= MAX_TEXT) { "invalid snapshotId" }
        require(request.components.size <= MAX_COMPONENTS) { "too many components" }
        request.components.forEach { component ->
            require(COMPONENT.matches(component.component)) { "invalid component" }
            require(
                component.declaredInfrastructure.all { it in INFRASTRUCTURE } &&
                    component.observedInfrastructureStarts >= 0 &&
                    component.observedInfrastructureStops >= 0 &&
                    component.observedInfrastructureStops <= component.observedInfrastructureStarts &&
                    component.flakyTests >= 0 &&
                    component.failingTests >= 0 &&
                    component.sameCommitTransitions >= 0 &&
                    component.wastedDurationMs >= 0,
            ) { "invalid infrastructure or historical metric" }
            require(
                component.evidence.size <= MAX_EVIDENCE_PER_COMPONENT &&
                    component.evidence.all {
                        it.kind in EVIDENCE_KINDS && it.state in EVIDENCE_STATES
                    },
            ) { "invalid evidence vocabulary" }
        }
    }

    companion object {
        private val EXECUTION_KINDS = setOf("unit", "integration", "e2e", "visual", "simulation")
        private val EVIDENCE_KINDS =
            EXECUTION_KINDS +
                setOf(
                    "contract",
                    "performance",
                    "synthetic",
                    "mutation",
                    "trace",
                )
        private val EVIDENCE_STATES = setOf("passed", "failed", "skipped", "not-run", "stale", "blocked", "unknown")
        private val INFRASTRUCTURE = setOf("postgres", "redpanda", "valkey")
        private val COMPONENT = Regex("^openbank-[a-z0-9-]{1,100}$")
        private const val MAX_COMPONENTS = 250
        private const val MAX_EVIDENCE_PER_COMPONENT = 20
        private const val MAX_TEXT = 200
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        /**
         * The id that makes a detached start idempotent for the day.
         *
         * Deliberately NOT `System.currentTimeMillis()`, which the operator path uses: an id that
         * can never collide can never dedupe either. The trigger is part of the id so an operator
         * can still force a run on a day the schedule has already used.
         */
        fun scheduledWorkflowId(trigger: RunTrigger, at: Instant): String =
            "flaky-test-hunter-check-${trigger.name.lowercase()}-${DAY.format(at)}"
    }
}
