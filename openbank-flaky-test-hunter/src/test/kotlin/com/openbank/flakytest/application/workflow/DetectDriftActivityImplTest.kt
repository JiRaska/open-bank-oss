// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.PactGatedTestClass
import com.openbank.flakytest.domain.model.PactProviderDeclaration
import com.openbank.flakytest.domain.model.RunBlockingViolation
import com.openbank.flakytest.domain.model.TestCountSample
import com.openbank.flakytest.domain.model.TestScanSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure snapshot-in, findings-out unit tests — no file I/O needed here (that lives in
 * [com.openbank.flakytest.infrastructure.adapter.TestScanAdapterTest]), since [DetectDriftActivityImpl]
 * only ever transforms an already-collected [TestScanSnapshot]. */
class DetectDriftActivityImplTest {

    private val activity = DetectDriftActivityImpl()

    private fun emptySnapshot() = TestScanSnapshot(
        testFilesScanned = 0,
        runBlockingViolations = emptyList(),
        pactGatedClasses = emptyList(),
        pactProviderDeclarations = emptyList(),
        testCountSamples = emptyList(),
    )

    @Test
    fun `two distinct files declaring the same Pact provider are flagged as a collision`() {
        val snapshot = emptySnapshot().copy(
            pactProviderDeclarations = listOf(
                PactProviderDeclaration(
                    file = "openbank-transaction-service/src/test/kotlin/.../HttpProviderTest.kt",
                    providerName = "openbank-transaction-service",
                ),
                PactProviderDeclaration(
                    file = "openbank-transaction-service/src/test/kotlin/.../MessageProviderTest.kt",
                    providerName = "openbank-transaction-service",
                ),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        val finding = findings.single()
        assertThat(finding.checkType).isEqualTo(FlakyTestCheckType.PACT_PROVIDER_CLASS_COLLISION)
        assertThat(finding.component).isEqualTo("openbank-transaction-service")
        assertThat(finding.title).contains("HttpProviderTest.kt").contains("MessageProviderTest.kt")
    }

    @Test
    fun `a single Pact provider declaration is not flagged as a collision`() {
        val snapshot = emptySnapshot().copy(
            pactProviderDeclarations = listOf(
                PactProviderDeclaration(
                    file = "openbank-billing-service/src/test/kotlin/.../BillingProviderTest.kt",
                    providerName = "openbank-billing-service",
                ),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).isEmpty()
    }

    @Test
    fun `two files declaring different providers are not flagged as a collision with each other`() {
        val snapshot = emptySnapshot().copy(
            pactProviderDeclarations = listOf(
                PactProviderDeclaration(file = "a/ProviderATest.kt", providerName = "openbank-a-service"),
                PactProviderDeclaration(file = "b/ProviderBTest.kt", providerName = "openbank-b-service"),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).isEmpty()
    }

    @Test
    fun `every runBlocking violation becomes a CRITICAL finding`() {
        val snapshot = emptySnapshot().copy(
            runBlockingViolations = listOf(
                RunBlockingViolation(
                    file = "openbank-sample-service/src/test/kotlin/.../SampleTest.kt",
                    line = 12,
                    builder = "runBlocking",
                    // Deliberately NOT a full function declaration here — that shape would itself trip
                    // check-test-runblocking-unit.sh's grep against this very file (see FlakyTestModelsTest).
                    snippet = "= runBlocking { 1 }",
                ),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING)
        assertThat(findings.single().component).isEqualTo("openbank-sample-service/src/test/kotlin/.../SampleTest.kt")
    }

    @Test
    fun `Pact-gated classes are grouped into one blind-spot finding per module`() {
        val snapshot = emptySnapshot().copy(
            pactGatedClasses = listOf(
                PactGatedTestClass(file = "a/T1.kt", module = "openbank-a-service", className = "T1"),
                PactGatedTestClass(file = "a/T2.kt", module = "openbank-a-service", className = "T2"),
                PactGatedTestClass(file = "b/T3.kt", module = "openbank-b-service", className = "T3"),
            ),
        )

        val findings = activity.detect(snapshot)
            .filter { it.checkType == FlakyTestCheckType.PACT_LOCAL_VERIFICATION_BLIND_SPOT }

        assertThat(findings).hasSize(2)
        val moduleA = findings.single { it.component == "openbank-a-service" }
        assertThat(moduleA.rawMetricValue.toInt()).isEqualTo(2)
    }

    @Test
    fun `a module with fewer executed than declared Test functions is flagged as count drift`() {
        val snapshot = emptySnapshot().copy(
            testCountSamples = listOf(
                TestCountSample(module = "openbank-sample-service", declaredCount = 5, executedCount = 3),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(FlakyTestCheckType.TEST_COUNT_DRIFT)
        assertThat(findings.single().rawMetricValue.toInt()).isEqualTo(3)
        assertThat(findings.single().threshold.toInt()).isEqualTo(5)
    }

    @Test
    fun `a module with more executed than declared Test functions is not flagged -- parameterized tests are benign`() {
        val snapshot = emptySnapshot().copy(
            testCountSamples = listOf(
                TestCountSample(module = "openbank-sample-service", declaredCount = 5, executedCount = 12),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).isEmpty()
    }

    @Test
    fun `an empty snapshot produces no findings`() {
        assertThat(activity.detect(emptySnapshot())).isEmpty()
    }
}
