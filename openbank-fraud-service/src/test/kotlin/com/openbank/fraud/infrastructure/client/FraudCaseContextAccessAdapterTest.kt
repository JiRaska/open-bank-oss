// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.infrastructure.client

import com.openbank.fraud.application.port.out.FraudAssignedCandidates
import com.openbank.fraud.application.port.out.FraudCaseAccessDecision
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FraudCaseContextAccessAdapterTest {
    private val client = mockk<FraudCaseContextAccessRestClient>()
    private val adapter = FraudCaseContextAccessAdapter().apply {
        this.client = this@FraudCaseContextAccessAdapterTest.client
    }
    private val id = UUID.randomUUID()
    private val bearer = "Bearer investigator-token"

    @Test
    fun `propagates exact case and human bearer and accepts only 204`(): Unit = runBlocking {
        coEvery { client.check(any(), any(), any(), any()) } returns Response.noContent().build()

        assertThat(adapter.check(id, bearer)).isEqualTo(FraudCaseAccessDecision.ALLOWED)
        coVerify(exactly = 1) { client.check(id, bearer, id.toString(), "FRAUD_INVESTIGATION") }
    }

    @Test
    fun `denial and unexpected responses fail closed`(): Unit = runBlocking {
        coEvery { client.check(any(), any(), any(), any()) } returns Response.status(403).build()
        assertThat(adapter.check(id, bearer)).isEqualTo(FraudCaseAccessDecision.DENIED)

        coEvery { client.check(any(), any(), any(), any()) } returns Response.status(200).build()
        assertThat(adapter.check(id, bearer)).isEqualTo(FraudCaseAccessDecision.UNAVAILABLE)

        coEvery { client.check(any(), any(), any(), any()) } throws IllegalStateException("Context unavailable")
        assertThat(adapter.check(id, bearer)).isEqualTo(FraudCaseAccessDecision.UNAVAILABLE)
    }

    @Test
    fun `candidate set is fetched from Context under human bearer and invalid sets fail closed`(): Unit = runBlocking {
        val assigned = UUID.randomUUID()
        coEvery { client.assignedCandidates(any(), any(), any(), any()) } returns
            FraudAssignedCandidates(listOf(assigned), false)
        assertThat(adapter.assignedCandidates(id, bearer)?.ids).containsExactly(assigned)
        coVerify(exactly = 1) { client.assignedCandidates(id, bearer, id.toString(), "FRAUD_INVESTIGATION") }

        coEvery { client.assignedCandidates(any(), any(), any(), any()) } returns
            FraudAssignedCandidates(listOf(id), false)
        assertThat(adapter.assignedCandidates(id, bearer)).isNull()

        coEvery { client.assignedCandidates(any(), any(), any(), any()) } throws
            IllegalStateException("Context unavailable")
        assertThat(adapter.assignedCandidates(id, bearer)).isNull()
    }
}
