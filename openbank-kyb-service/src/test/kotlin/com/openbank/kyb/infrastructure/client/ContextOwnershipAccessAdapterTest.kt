// SPDX-License-Identifier: Apache-2.0
package com.openbank.kyb.infrastructure.client

import com.openbank.kyb.application.port.out.UboObservationAccessDecision
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ContextOwnershipAccessAdapterTest {
    private val client = mockk<ContextOwnershipAccessRestClient>()
    private val adapter = ContextOwnershipAccessAdapter().apply {
        this.client = this@ContextOwnershipAccessAdapterTest.client
    }
    private val caseId = UUID.randomUUID()
    private val bearer = "Bearer kyb-reviewer-token"

    @Test
    fun `only data-free access decision allows the exact case and human bearer`(): Unit = runBlocking {
        coEvery { client.check(any(), any(), any(), any()) } returns Response.noContent().build()

        assertThat(adapter.check(caseId, bearer)).isEqualTo(UboObservationAccessDecision.ALLOWED)
        coVerify(exactly = 1) { client.check(caseId, bearer, caseId.toString(), "KYB_OWNERSHIP_REVIEW") }
    }

    @Test
    fun `history response denial and outage do not grant access`(): Unit = runBlocking {
        coEvery { client.check(any(), any(), any(), any()) } returns Response.ok().build()
        assertThat(adapter.check(caseId, bearer)).isEqualTo(UboObservationAccessDecision.UNAVAILABLE)

        coEvery { client.check(any(), any(), any(), any()) } returns Response.status(403).build()
        assertThat(adapter.check(caseId, bearer)).isEqualTo(UboObservationAccessDecision.DENIED)

        coEvery { client.check(any(), any(), any(), any()) } throws IllegalStateException("Context unavailable")
        assertThat(adapter.check(caseId, bearer)).isEqualTo(UboObservationAccessDecision.UNAVAILABLE)
    }
}
