// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class FraudCaseSourceEvidenceTest {
    private val client = mockk<FraudCaseSourceClient>()
    private val serviceTokens = mockk<FraudCaseServiceToken>()
    private val source = FraudCaseSourceEvidence(client, serviceTokens)
    private val id = UUID.randomUUID()
    private val bearer = "Bearer investigator-token"

    @Test
    fun `accepts only matching open source evidence`(): Unit = runBlocking {
        every { client.evidence(id, bearer, "FRAUD_INVESTIGATION") } returns Uni.createFrom().item(snapshot(id))

        assertThat(source.read(id, bearer).caseId).isEqualTo(id)
    }

    @Test
    fun `wrong case or closed state never becomes graph evidence`(): Unit = runBlocking {
        every { client.evidence(id, bearer, "FRAUD_INVESTIGATION") } returns
            Uni.createFrom().item(snapshot(UUID.randomUUID()))
        assertThatThrownBy { runBlocking { source.read(id, bearer) } }
            .isInstanceOf(FraudCaseSourceUnavailable::class.java)

        every { client.evidence(id, bearer, "FRAUD_INVESTIGATION") } returns
            Uni.createFrom().item(snapshot(id).copy(closedAt = Instant.parse("2026-09-18T00:00:00Z")))
        assertThatThrownBy { runBlocking { source.read(id, bearer) } }
            .isInstanceOf(FraudCaseSourceUnavailable::class.java)

        every { client.evidence(id, bearer, "FRAUD_INVESTIGATION") } returns
            Uni.createFrom().item(snapshot(id).copy(status = "CLOSED_NO_FINDING"))
        assertThatThrownBy { runBlocking { source.read(id, bearer) } }
            .isInstanceOf(FraudCaseSourceUnavailable::class.java)
    }

    @Test
    fun `source denial and outage stay distinct but release no evidence`(): Unit = runBlocking {
        every { client.evidence(id, bearer, "FRAUD_INVESTIGATION") } returns
            Uni.createFrom().failure(WebApplicationException(403))
        assertThatThrownBy { runBlocking { source.read(id, bearer) } }
            .isInstanceOf(FraudCaseSourceDenied::class.java)

        every { client.evidence(id, bearer, "FRAUD_INVESTIGATION") } returns
            Uni.createFrom().failure(IllegalStateException("offline"))
        assertThatThrownBy { runBlocking { source.read(id, bearer) } }
            .isInstanceOf(FraudCaseSourceUnavailable::class.java)
    }

    @Test
    fun `source match response is bounded and cannot include the root`(): Unit = runBlocking {
        val assigned = UUID.randomUUID()
        coEvery { serviceTokens.bearer() } returns "Bearer context-service-token"
        every {
            client.matchAssigned(id, "Bearer context-service-token", bearer, "FRAUD_INVESTIGATION")
        } returns
            Uni.createFrom().item(FraudAssignedMatchResponse(listOf(id), 1, false))
        assertThatThrownBy { runBlocking { source.matchAssigned(id, bearer) } }
            .isInstanceOf(FraudCaseSourceUnavailable::class.java)

        every {
            client.matchAssigned(id, "Bearer context-service-token", bearer, "FRAUD_INVESTIGATION")
        } returns
            Uni.createFrom().item(FraudAssignedMatchResponse(listOf(assigned), 1, false))
        assertThat(source.matchAssigned(id, bearer).candidateIds).containsExactly(assigned)
    }

    private fun snapshot(caseId: UUID) = FraudCaseSourceSnapshot(
        caseId = caseId,
        scoreId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        status = "OPEN",
        revision = 1,
        openedAt = Instant.parse("2026-09-17T00:00:00Z"),
    )
}
