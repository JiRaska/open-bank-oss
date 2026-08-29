// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.flakytest.application.usecase

import com.openbank.flakytest.application.port.out.FindingRepository
import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.TestIntelligenceAnalysisRequest
import com.openbank.flakytest.domain.model.TestIntelligenceComponentInput
import com.openbank.flakytest.domain.model.TestIntelligenceEvidenceInput
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TestIntelligenceAnalysisTest {
    private val repository = mockk<FindingRepository>()
    private val llm = mockk<LlmDiagnosisPort>()
    private val exporter = RecordingSpanExporter()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    private val service = FlakyTestHunterService(
        mockk<WorkflowClient>(),
        mockk<TemporalConfig>(),
        repository,
        llm,
        Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
        tracerProvider.get("test"),
    )

    @Test
    fun `money-path gaps are diagnosed but never auto-proposed`(): Unit = runBlocking {
        coEvery { repository.findById(any()) } returns null
        coEvery { llm.diagnose(any(), any()) } returns "The run proves no execution or container start."
        coEvery { repository.save(any()) } answers { firstArg() }

        val findings = service.analyze(analysisRequest())

        assertThat(findings.map { it.checkType }).containsExactlyInAnyOrder(
            FlakyTestCheckType.MISSING_EXECUTION_EVIDENCE,
            FlakyTestCheckType.FAILED_TEST_EVIDENCE,
            FlakyTestCheckType.OBSERVED_FAILING_TESTS,
            FlakyTestCheckType.OBSERVED_FLAKY_TESTS,
            FlakyTestCheckType.STALE_TEST_EVIDENCE,
            FlakyTestCheckType.UNPROVEN_TEST_INFRASTRUCTURE,
        )
        assertThat(findings.filter { it.checkType != FlakyTestCheckType.STALE_TEST_EVIDENCE }).allSatisfy {
            assertThat(it.severity).isEqualTo(FindingSeverity.CRITICAL)
        }
        assertThat(findings.single { it.checkType == FlakyTestCheckType.STALE_TEST_EVIDENCE }.severity)
            .isEqualTo(FindingSeverity.WARNING)
        assertThat(findings.single { it.checkType == FlakyTestCheckType.OBSERVED_FLAKY_TESTS }.rawMetricValue)
            .isEqualByComparingTo("2")
        assertThat(findings.single { it.checkType == FlakyTestCheckType.OBSERVED_FLAKY_TESTS }.title)
            .contains("3 same-commit transition(s)", "4200 ms")
        assertThat(findings.single { it.checkType == FlakyTestCheckType.OBSERVED_FAILING_TESTS }.rawMetricValue)
            .isEqualByComparingTo("1")
        assertThat(findings).allSatisfy {
            assertThat(it.status).isEqualTo(FindingStatus.DIAGNOSED)
            assertThat(it.proposalUrl).isNull()
            assertThat(it.rootCause).isNotBlank()
        }
    }

    @Test
    fun `started Testcontainers without matching stops is a distinct evidence finding`(): Unit = runBlocking {
        coEvery { repository.findById(any()) } returns null
        coEvery { llm.diagnose(any(), any()) } returns "The retained lifecycle evidence is incomplete."
        coEvery { repository.save(any()) } answers { firstArg() }

        val findings = service.analyze(
            TestIntelligenceAnalysisRequest(
                snapshotId = "run-lifecycle",
                collectedAt = Instant.parse("2026-08-22T11:00:00Z"),
                components = listOf(
                    TestIntelligenceComponentInput(
                        component = "openbank-ledger-service",
                        moneyPath = true,
                        evidence = listOf(TestIntelligenceEvidenceInput("integration", "passed")),
                        declaredInfrastructure = listOf("postgres"),
                        observedInfrastructureStarts = 2,
                        observedInfrastructureStops = 1,
                    ),
                ),
            ),
        )

        val finding = findings.single()
        assertThat(finding.checkType).isEqualTo(FlakyTestCheckType.UNTERMINATED_TEST_INFRASTRUCTURE)
        assertThat(finding.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(finding.rawMetricValue).isEqualByComparingTo("1")
        assertThat(finding.title).contains("2 test-infrastructure start", "1 stop")
    }

    @Test
    fun `findings read emits a bounded trace contract after repository execution`(): Unit = runBlocking {
        coEvery { repository.findActive() } returns emptyList()

        service.getActive()

        exporter.contract()
            .requiresSpan("flaky-test-hunter.findings.read")
            .requiresAttribute("flaky-test-hunter.findings.read", "openbank.flaky.findings.count")
            .hasNoErrorSpan()
            .verifiedAs("flaky-findings-read")
    }

    private fun analysisRequest() = TestIntelligenceAnalysisRequest(
        snapshotId = "run-42",
        collectedAt = Instant.parse("2026-08-22T11:00:00Z"),
        components = listOf(
            TestIntelligenceComponentInput(
                component = "openbank-ledger-service",
                moneyPath = true,
                evidence = listOf(
                    TestIntelligenceEvidenceInput("contract", "stale"),
                    TestIntelligenceEvidenceInput("contract", "failed"),
                    TestIntelligenceEvidenceInput("mutation", "failed"),
                    TestIntelligenceEvidenceInput("trace", "passed"),
                ),
                declaredInfrastructure = listOf("postgres"),
                observedInfrastructureStarts = 0,
                flakyTests = 2,
                failingTests = 1,
                sameCommitTransitions = 3,
                wastedDurationMs = 4200,
            ),
            TestIntelligenceComponentInput(
                component = "openbank-app",
                moneyPath = true,
                evidence = listOf(TestIntelligenceEvidenceInput("visual", "passed")),
                declaredInfrastructure = emptyList(),
                observedInfrastructureStarts = 0,
            ),
        ),
    )
}
