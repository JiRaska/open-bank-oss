// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class FlakyTestModelsTest {

    @Test
    fun `FlakyTestFinding defaults to OPEN status`() {
        val finding = FlakyTestFinding(
            id = "test-id",
            checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.now(),
            title = "ApprovalResourceTest.kt:42 uses the unsafe '= runBlocking {' form without ': Unit'",
            component = "openbank-transaction-service/src/test/kotlin/.../ApprovalResourceTest.kt",
            filePath = "openbank-transaction-service/src/test/kotlin/.../ApprovalResourceTest.kt",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
        )
        assertThat(finding.status).isEqualTo(FindingStatus.OPEN)
        assertThat(finding.rootCause).isNull()
        assertThat(finding.proposalUrl).isNull()
    }

    @Test
    fun `FlakyTestReport counts proposed findings`() {
        val now = Instant.now()
        val finding = FlakyTestFinding(
            id = "f1",
            checkType = FlakyTestCheckType.PACT_PROVIDER_CLASS_COLLISION,
            severity = FindingSeverity.CRITICAL,
            detectedAt = now,
            title = "Provider 'openbank-transaction-service' has 2 separate @Provider test classes",
            component = "openbank-transaction-service",
            filePath = "TransactionPactProviderVerificationTest.kt",
            rawMetricValue = BigDecimal(2),
            threshold = BigDecimal.ONE,
            status = FindingStatus.PROPOSED,
        )
        val report = FlakyTestReport(
            runId = "run-1",
            startedAt = now,
            completedAt = now,
            testFilesScanned = 120,
            findingsDetected = listOf(finding),
            findingsProposed = 1,
            tokensUsed = 0,
            trigger = RunTrigger.SCHEDULED,
        )
        assertThat(report.findingsProposed).isEqualTo(1)
        assertThat(report.findingsDetected).hasSize(1)
        assertThat(report.testFilesScanned).isEqualTo(120)
    }

    @Test
    fun `FlakyTestCheckType enum covers implemented source and evidence checks`() {
        assertThat(FlakyTestCheckType.entries).containsExactlyInAnyOrder(
            FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
            FlakyTestCheckType.PACT_LOCAL_VERIFICATION_BLIND_SPOT,
            FlakyTestCheckType.PACT_PROVIDER_CLASS_COLLISION,
            FlakyTestCheckType.TEST_COUNT_DRIFT,
            FlakyTestCheckType.MISSING_EXECUTION_EVIDENCE,
            FlakyTestCheckType.FAILED_TEST_EVIDENCE,
            FlakyTestCheckType.OBSERVED_FAILING_TESTS,
            FlakyTestCheckType.OBSERVED_FLAKY_TESTS,
            FlakyTestCheckType.STALE_TEST_EVIDENCE,
            FlakyTestCheckType.UNPROVEN_TEST_INFRASTRUCTURE,
            FlakyTestCheckType.UNTERMINATED_TEST_INFRASTRUCTURE,
            FlakyTestCheckType.REQUIRED_CONTROL_GAP,
        )
    }

    @Test
    fun `RunBlockingViolation carries the coroutine builder that was matched`() {
        val violation = RunBlockingViolation(
            file = "openbank-standing-order-service/src/test/kotlin/.../ConsumerTest.kt",
            line = 88,
            builder = "runBlocking",
            // Deliberately NOT a full function declaration here — that shape would itself trip
            // check-test-runblocking-unit.sh's grep against this very file.
            snippet = "= runBlocking { assertThat(consumed).isTrue() }",
        )
        assertThat(violation.builder).isEqualTo("runBlocking")
        assertThat(violation.line).isEqualTo(88)
    }

    @Test
    fun `PactGatedTestClass and PactProviderDeclaration tag which module and provider they belong to`() {
        val gated = PactGatedTestClass(
            file = "openbank-transaction-service/src/test/kotlin/.../ProviderTest.kt",
            module = "openbank-transaction-service",
            className = "TransactionPactProviderVerificationTest",
        )
        val declared = PactProviderDeclaration(
            file = "openbank-transaction-service/src/test/kotlin/.../ProviderTest.kt",
            providerName = "openbank-transaction-service",
        )
        assertThat(gated.module).isEqualTo("openbank-transaction-service")
        assertThat(declared.providerName).isEqualTo("openbank-transaction-service")
    }

    @Test
    fun `TestScanSnapshot defaults to empty collections when nothing is found`() {
        val snapshot = TestScanSnapshot(
            testFilesScanned = 0,
            runBlockingViolations = emptyList(),
            pactGatedClasses = emptyList(),
            pactProviderDeclarations = emptyList(),
            testCountSamples = emptyList(),
        )
        assertThat(snapshot.runBlockingViolations).isEmpty()
        assertThat(snapshot.testCountSamples).isEmpty()
    }

    @Test
    fun `TestCountSample carries declared and executed counts independently`() {
        val sample = TestCountSample(module = "openbank-billing-service", declaredCount = 42, executedCount = 39)
        assertThat(sample.declaredCount).isEqualTo(42)
        assertThat(sample.executedCount).isEqualTo(39)
    }
}
