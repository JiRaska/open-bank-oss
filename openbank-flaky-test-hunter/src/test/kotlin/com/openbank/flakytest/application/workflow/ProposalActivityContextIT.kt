// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.application.port.out.GitHubProposalPort
import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.openbank.flakytest.infrastructure.persistence.PostgresFindingRepository
import com.openbank.flakytest.infrastructure.policy.PrPathPolicyGate
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.testing.containers.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.vertx.core.Vertx
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ProposalActivityContextIT {

    @Inject
    lateinit var repository: PostgresFindingRepository

    @Inject
    lateinit var config: FlakyTestHunterConfig

    @Inject
    lateinit var dataSource: AgroalDataSource

    @Test
    fun `proposal persists after an IO port suspends outside the Vertx context`() {
        val llm = mockk<LlmDiagnosisPort>()
        val github = mockk<GitHubProposalPort>()
        coEvery { llm.diagnose(any(), any()) } returns "Human triage is required"
        coEvery { llm.proposeFixDiff(any(), any()) } returns null
        val proposalUrl = "https://example.invalid/triage/context-regression"
        coEvery { github.openTicket(any(), any()) } coAnswers {
            withContext(Dispatchers.IO) {
                assertThat(Vertx.currentContext()).isNull()
                proposalUrl
            }
        }
        // Exercise the production bridge, never the runBlocking override used by unit tests.
        val activity = DiagnoseAndProposeActivityImpl(
            llm,
            github,
            repository,
            mockk<AuditEventPublisher>(relaxed = true),
            config,
            mockk<PrPathPolicyGate>(),
        )
        val finding = FlakyTestFinding(
            id = UUID.randomUUID().toString(),
            checkType = FlakyTestCheckType.PACT_PROVIDER_CLASS_COLLISION,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.now(),
            title = "Context preservation regression fixture",
            component = "openbank-flaky-test-hunter",
            filePath = "openbank-flaky-test-hunter/src/test/kotlin/ContextFixture.kt",
            rawMetricValue = BigDecimal.TWO,
            threshold = BigDecimal.ONE,
        )

        assertThat(Vertx.currentContext()).isNull()
        val diagnosed = activity.diagnose(finding, emptyMap())
        val proposed = activity.propose(diagnosed)

        assertThat(proposed.status).isEqualTo(FindingStatus.PROPOSED)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT status, proposal_url FROM findings WHERE id = ?").use { query ->
                query.setObject(1, UUID.fromString(finding.id))
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("status")).isEqualTo("PROPOSED")
                    assertThat(rows.getString("proposal_url")).isEqualTo(proposalUrl)
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }
}
