// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.usecase

import com.openbank.flakytest.application.port.out.FindingRepository
import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.flakytest.domain.model.TestIntelligenceAnalysisRequest
import com.openbank.flakytest.domain.model.TestIntelligenceComponentInput
import com.openbank.flakytest.domain.model.TestIntelligenceEvidenceInput
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The bounded-request rejection surface of [FlakyTestHunterService.analyze] plus the observable
 * behaviour of the findings read. Every case here is a `require` the production code carries: a
 * regression that widens the vocabulary, drops a bound or stops null-checking a list element makes
 * exactly one of these tests go red.
 */
class TestIntelligenceValidationTest {

    private val repository = mockk<FindingRepository>()
    private val llm = mockk<LlmDiagnosisPort>()
    private val exporter = RecordingSpanExporter()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    private val clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC)
    private val service = FlakyTestHunterService(
        mockk<WorkflowClient>(),
        mockk<TemporalConfig>(),
        repository,
        llm,
        clock,
        tracerProvider.get("test"),
    )

    private fun component(
        name: String = "openbank-ledger-service",
        evidence: List<TestIntelligenceEvidenceInput?> = listOf(TestIntelligenceEvidenceInput("unit", "passed")),
        requiredControls: List<TestIntelligenceEvidenceInput?> = emptyList(),
        infrastructure: List<String> = emptyList(),
        starts: Int = 0,
        stops: Int = 0,
        flaky: Int = 0,
        failing: Int = 0,
        sameCommitTransitions: Int = 0,
        wastedDurationMs: Int = 0,
    ) = TestIntelligenceComponentInput(
        component = name,
        moneyPath = false,
        evidence = evidence,
        requiredControls = requiredControls,
        declaredInfrastructure = infrastructure,
        observedInfrastructureStarts = starts,
        observedInfrastructureStops = stops,
        flakyTests = flaky,
        failingTests = failing,
        sameCommitTransitions = sameCommitTransitions,
        wastedDurationMs = wastedDurationMs,
    )

    private fun request(
        snapshotId: String = "run-1",
        components: List<TestIntelligenceComponentInput?> = listOf(component()),
    ) = TestIntelligenceAnalysisRequest(
        snapshotId = snapshotId,
        collectedAt = Instant.parse("2026-08-22T11:00:00Z"),
        components = components,
    )

    private fun rejects(message: String, request: TestIntelligenceAnalysisRequest) {
        assertThatThrownBy { runBlocking { service.analyze(request) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(message)
    }

    @Test
    fun `a blank or over-long snapshot id is rejected`() {
        rejects("invalid snapshotId", request(snapshotId = "   "))
        rejects("invalid snapshotId", request(snapshotId = "s".repeat(201)))
    }

    @Test
    fun `a snapshot id at the length bound is accepted`(): Unit = runBlocking {
        // 200 characters is the documented ceiling, not the first rejected value — an off-by-one
        // that tightened the bound would silently 400 a legitimate caller.
        val findings = service.analyze(request(snapshotId = "s".repeat(200)))
        assertThat(findings).isEmpty()
    }

    @Test
    fun `more components than the ceiling is rejected before any component is inspected`() {
        rejects("too many components", request(components = List(251) { component() }))
    }

    @Test
    fun `a null component element is rejected rather than deserialised into a null hole`() {
        rejects("components[0] must not be null", request(components = listOf(null)))
    }

    @Test
    fun `a null evidence or required-control element is rejected`() {
        rejects("evidence[0] must not be null", request(components = listOf(component(evidence = listOf(null)))))
        rejects(
            "requiredControls[0] must not be null",
            request(components = listOf(component(requiredControls = listOf(null)))),
        )
    }

    @Test
    fun `a component name outside the openbank namespace is rejected`() {
        listOf("ledger-service", "openbank-Ledger", "openbank-", "openbank-a_b").forEach { name ->
            rejects("invalid component", request(components = listOf(component(name = name))))
        }
    }

    @Test
    fun `an undeclared infrastructure kind is rejected while the three known ones pass`(): Unit = runBlocking {
        rejects(
            "invalid infrastructure",
            request(components = listOf(component(infrastructure = listOf("kafka"), starts = 1, stops = 1))),
        )

        val findings = service.analyze(
            request(
                components = listOf(
                    component(infrastructure = listOf("postgres", "redpanda", "valkey"), starts = 1, stops = 1),
                ),
            ),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `negative counters and more stops than starts are rejected`() {
        rejects("invalid infrastructure", request(components = listOf(component(starts = -1))))
        rejects("invalid infrastructure", request(components = listOf(component(starts = 1, stops = 2))))
        rejects("invalid infrastructure", request(components = listOf(component(flaky = -1))))
        rejects("invalid infrastructure", request(components = listOf(component(failing = -1))))
        rejects("invalid infrastructure", request(components = listOf(component(sameCommitTransitions = -1))))
        rejects("invalid infrastructure", request(components = listOf(component(wastedDurationMs = -1))))
    }

    @Test
    fun `an unknown evidence kind or state is rejected`() {
        rejects(
            "invalid evidence vocabulary",
            request(components = listOf(component(evidence = listOf(TestIntelligenceEvidenceInput("chaos", "passed"))))),
        )
        rejects(
            "invalid evidence vocabulary",
            request(
                components = listOf(component(evidence = listOf(TestIntelligenceEvidenceInput("unit", "flaky")))),
            ),
        )
        rejects(
            "invalid evidence vocabulary",
            request(components = listOf(component(evidence = List(21) { TestIntelligenceEvidenceInput("unit", "passed") }))),
        )
    }

    @Test
    fun `coverage and runtime are required-control kinds only, never evidence kinds`() {
        // The two vocabularies deliberately differ: REQUIRED_CONTROL_KINDS extends EVIDENCE_KINDS.
        rejects(
            "invalid evidence vocabulary",
            request(
                components = listOf(component(evidence = listOf(TestIntelligenceEvidenceInput("coverage", "passed")))),
            ),
        )
        rejects(
            "invalid required-control vocabulary",
            request(
                components = listOf(
                    component(requiredControls = listOf(TestIntelligenceEvidenceInput("postgres", "passed"))),
                ),
            ),
        )
    }

    @Test
    fun `a component with only passing execution evidence yields no finding at all`(): Unit = runBlocking {
        val findings = service.analyze(
            request(
                components = listOf(
                    component(
                        evidence = listOf(TestIntelligenceEvidenceInput("e2e", "passed")),
                        requiredControls = listOf(TestIntelligenceEvidenceInput("coverage", "passed")),
                        infrastructure = listOf("postgres"),
                        starts = 2,
                        stops = 2,
                    ),
                ),
            ),
        )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `a non-execution evidence kind alone still counts as missing execution evidence`(): Unit = runBlocking {
        coEvery { repository.findById(any()) } returns null
        coEvery { llm.diagnose(any(), any()) } returns "no execution"
        coEvery { repository.save(any()) } answers { firstArg() }

        val findings = service.analyze(
            request(components = listOf(component(evidence = listOf(TestIntelligenceEvidenceInput("contract", "passed"))))),
        )

        assertThat(findings.map { it.checkType })
            .containsExactly(FlakyTestCheckType.MISSING_EXECUTION_EVIDENCE)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.WARNING)
        assertThat(findings.single().filePath).isEqualTo("test-intelligence:run-1")
    }

    @Test
    fun `an already-stored finding is returned untouched and never re-diagnosed`(): Unit = runBlocking {
        val stored = FlakyTestFinding(
            id = "0eb5a3d3-0000-0000-0000-000000000000",
            checkType = FlakyTestCheckType.MISSING_EXECUTION_EVIDENCE,
            severity = FindingSeverity.WARNING,
            detectedAt = Instant.parse("2026-08-01T00:00:00Z"),
            title = "already known",
            component = "openbank-ledger-service",
            filePath = "test-intelligence:run-1",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
            status = FindingStatus.PROPOSED,
        )
        coEvery { repository.findById(any()) } returns stored

        val findings = service.analyze(
            request(components = listOf(component(evidence = listOf(TestIntelligenceEvidenceInput("contract", "passed"))))),
        )

        assertThat(findings).containsExactly(stored)
        coVerify(exactly = 0) { llm.diagnose(any(), any()) }
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `the diagnosis context carries a non-negative snapshot age even for a future snapshot`(): Unit = runBlocking {
        coEvery { repository.findById(any()) } returns null
        coEvery { repository.save(any()) } answers { firstArg() }
        val contexts = mutableListOf<Map<String, Double>>()
        coEvery { llm.diagnose(any(), capture(contexts)) } returns "diagnosis"

        service.analyze(
            TestIntelligenceAnalysisRequest(
                snapshotId = "future",
                // Ahead of the fixed clock: a naive Duration.between would be negative.
                collectedAt = Instant.parse("2026-08-22T13:00:00Z"),
                components = listOf(component(evidence = listOf(TestIntelligenceEvidenceInput("contract", "passed")))),
            ),
        )

        assertThat(contexts.single()["snapshot_age_seconds"]).isEqualTo(0.0)
    }

    @Test
    fun `the finding id is stable for the same snapshot, component and check type`(): Unit = runBlocking {
        coEvery { repository.findById(any()) } returns null
        coEvery { llm.diagnose(any(), any()) } returns "diagnosis"
        coEvery { repository.save(any()) } answers { firstArg() }
        val evidence = listOf(TestIntelligenceEvidenceInput("contract", "passed"))

        val first = service.analyze(request(components = listOf(component(evidence = evidence)))).single()
        val second = service.analyze(request(components = listOf(component(evidence = evidence)))).single()
        val otherSnapshot = service.analyze(
            request(snapshotId = "run-2", components = listOf(component(evidence = evidence))),
        ).single()

        assertThat(first.id).isEqualTo(second.id)
        assertThat(first.id).isNotEqualTo(otherSnapshot.id)
    }

    @Test
    fun `a failing findings read records an error span and rethrows`() {
        coEvery { repository.findActive() } throws IllegalStateException("db down")

        assertThatThrownBy { runBlocking { service.getActive() } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("db down")

        // The span must be ended AND marked as an error — a swallowed failure would leave the
        // contract's error assertion satisfied.
        assertThatThrownBy { exporter.contract().requiresSpan("flaky-test-hunter.findings.read").hasNoErrorSpan() }
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `getById delegates the raw id to the repository`(): Unit = runBlocking {
        coEvery { repository.findById("missing") } returns null

        assertThat(service.getById("missing")).isNull()
        coVerify { repository.findById("missing") }
    }

    @Test
    fun `an idempotency key on a non-operator trigger is rejected before any workflow is started`() {
        assertThatThrownBy {
            runBlocking { service.startDetached(RunTrigger.SCHEDULED, "flaky-test-hunter-operator-manual-2026-08-22") }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("only supported for operator workflows")
    }
}
