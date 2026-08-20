// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseDeliveryMode
import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import com.openbank.libs.temporal.TemporalConfig
import io.mockk.every
import io.mockk.mockk
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Optional
import javax.sql.DataSource

class ShadowPilotPreflightTest {

    private val client = mockk<WorkflowClient>()
    private val temporal = mockk<TemporalConfig>()
    private val config = mockk<CaseCoordinatorConfig>()
    private val caseGroup = mockk<CaseCoordinatorConfig.CaseGroup>()
    private val dataSource = mockk<DataSource>()

    @Test
    fun `shadow startup refuses an existing legacy case workflow`() {
        every { config.case() } returns caseGroup
        every { caseGroup.deliveryMode() } returns CaseDeliveryMode.SHADOW
        every { caseGroup.shadowRolloutId() } returns Optional.of("shadow-test")
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val result = mockk<ResultSet>()
        every { dataSource.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        every { statement.setString(any(), any()) } returns Unit
        every { statement.executeQuery() } returns result
        every { result.next() } returns true
        every { result.getBoolean(1) } returns false
        every { temporal.enabled() } returns true
        every { client.options } returns WorkflowClientOptions.newBuilder().setNamespace("openbank").build()
        val stubs = mockk<WorkflowServiceStubs>()
        val blocking = mockk<io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub>()
        every { client.workflowServiceStubs } returns stubs
        every { stubs.blockingStub() } returns blocking
        every { blocking.listOpenWorkflowExecutions(any()) } returns ListOpenWorkflowExecutionsResponse.newBuilder()
            .addExecutions(io.temporal.api.workflow.v1.WorkflowExecutionInfo.getDefaultInstance())
            .build()

        assertThatIllegalStateException().isThrownBy { ShadowPilotPreflight(client, temporal, config, dataSource) }
            .withMessageContaining("legacy case workflow")
    }
}
