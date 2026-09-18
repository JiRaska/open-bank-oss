// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.infrastructure.rest

import com.openbank.fraud.application.port.out.FraudCaseAccessDecision
import com.openbank.fraud.application.port.out.FraudCaseContextAccess
import com.openbank.fraud.application.port.out.FraudInvestigationCaseStore
import com.openbank.fraud.domain.model.FraudInvestigationCase
import com.openbank.fraud.domain.model.FraudInvestigationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class FraudInvestigationEvidenceResourceTest {
    private val cases = mockk<FraudInvestigationCaseStore>()
    private val access = mockk<FraudCaseContextAccess>()
    private val resource = FraudInvestigationCaseResource(cases, access, mockk<SecurityIdentity>())
    private val id = UUID.randomUUID()
    private val bearer = "Bearer investigator-token"

    @Test
    fun `status read requires the same live case assignment as evidence`(): Unit = runBlocking {
        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.DENIED
        val denied = resource.get(id, "FRAUD_INVESTIGATION", bearer)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.entity).isNull()
        coVerify(exactly = 0) { cases.find(any()) }

        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.UNAVAILABLE
        val unavailable = resource.get(id, "FRAUD_INVESTIGATION", bearer)
        assertThat(unavailable.status).isEqualTo(503)
        assertThat(unavailable.entity).isNull()
        coVerify(exactly = 0) { cases.find(any()) }
    }

    @Test
    fun `assigned status read returns only the requested case`(): Unit = runBlocking {
        val scoreId = UUID.randomUUID()
        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.ALLOWED
        coEvery { cases.find(id) } returns FraudInvestigationCase(
            id, scoreId, UUID.randomUUID(), null,
            FraudInvestigationStatus.CLOSED_NO_FINDING, 2, "admin-1", Instant.EPOCH, "admin-2", Instant.EPOCH,
        )

        val response = resource.get(id, "FRAUD_INVESTIGATION", bearer)
        val status = response.entity as FraudInvestigationCaseResponse
        assertThat(response.status).isEqualTo(200)
        assertThat(status.caseId).isEqualTo(id)
        assertThat(status.scoreId).isEqualTo(scoreId)
        coVerify(exactly = 1) { cases.find(id) }
    }

    @Test
    fun `closing a case requires live assignment before mutation`(): Unit = runBlocking {
        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.DENIED
        val denied = resource.closeWithoutFinding(id, "FRAUD_INVESTIGATION", "idem-1", bearer)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.entity).isNull()
        coVerify(exactly = 0) { cases.closeWithoutFinding(any(), any()) }

        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.UNAVAILABLE
        val unavailable = resource.closeWithoutFinding(id, "FRAUD_INVESTIGATION", "idem-1", bearer)
        assertThat(unavailable.status).isEqualTo(503)
        assertThat(unavailable.entity).isNull()
        coVerify(exactly = 0) { cases.closeWithoutFinding(any(), any()) }
    }

    @Test
    fun `denial and outage reveal no source associations`(): Unit = runBlocking {
        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.DENIED
        val denied = resource.evidence(id, "FRAUD_INVESTIGATION", bearer)
        assertThat(denied.status).isEqualTo(403)
        assertThat(denied.entity).isNull()
        coVerify(exactly = 0) { cases.find(any()) }

        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.UNAVAILABLE
        val unavailable = resource.evidence(id, "FRAUD_INVESTIGATION", bearer)
        assertThat(unavailable.status).isEqualTo(503)
        assertThat(unavailable.entity).isNull()
        coVerify(exactly = 0) { cases.find(any()) }
    }

    @Test
    fun `allowed exact case returns source associations as leads`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.ALLOWED
        coEvery { cases.find(id) } returns FraudInvestigationCase(
            id, UUID.randomUUID(), accountId, counterpartyId,
            FraudInvestigationStatus.OPEN, 1, "admin-1", Instant.EPOCH, null, null,
        )

        val response = resource.evidence(id, "FRAUD_INVESTIGATION", bearer)
        val evidence = response.entity as FraudInvestigationEvidenceResponse
        assertThat(response.status).isEqualTo(200)
        assertThat(evidence.caseId).isEqualTo(id)
        assertThat(evidence.accountId).isEqualTo(accountId)
        assertThat(evidence.counterpartyId).isEqualTo(counterpartyId)
        assertThat(evidence.toString()).doesNotContain("finding=true", "amount=", "reason=")
    }

    @Test
    fun `closed case associations are not returned as an active investigation`(): Unit = runBlocking {
        coEvery { access.check(id, bearer) } returns FraudCaseAccessDecision.ALLOWED
        coEvery { cases.find(id) } returns FraudInvestigationCase(
            id, UUID.randomUUID(), UUID.randomUUID(), null,
            FraudInvestigationStatus.CLOSED_NO_FINDING, 2, "admin-1", Instant.EPOCH, "admin-2", Instant.EPOCH,
        )

        val response = resource.evidence(id, "FRAUD_INVESTIGATION", bearer)
        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity).isNull()
    }
}
