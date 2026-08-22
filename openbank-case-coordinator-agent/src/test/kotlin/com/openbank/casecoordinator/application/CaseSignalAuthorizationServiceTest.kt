// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyDecision
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyPort
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyQuery
import com.openbank.casecoordinator.domain.model.CaseSignalEvidence
import com.openbank.casecoordinator.domain.model.CaseSignalEvidenceStage
import com.openbank.casecoordinator.infrastructure.persistence.CaseAuthorizationContext
import com.openbank.casecoordinator.infrastructure.persistence.CaseSignalEvidenceRepository
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CaseSignalAuthorizationServiceTest {
    private val policy = mockk<CaseCollaborationPolicyPort>()
    private val localGate = mockk<CaseCapabilityGate>()
    private val repository = mockk<CaseSignalEvidenceRepository>(relaxed = true)
    private val audit = mockk<AuditEventPublisher>()
    private val service = CaseSignalAuthorizationService(policy, localGate, repository, audit)

    @Test
    fun `allow requires policy local charter and remaining quota and emits correlated audit`(): Unit = runBlocking {
        every { repository.findContext("case-1") } returns CaseAuthorizationContext("INCIDENT_RESPONSE", "SHADOW")
        every { repository.tryRecordAuthorized(any(), 8) } returns true
        every { localGate.canContribute("rca-investigator") } returns true
        every { policy.decide(any()) } returns CaseCollaborationPolicyDecision(
            allow = true,
            reason = "allowed by charter and rules matrix",
            decisionId = "opa-decision-7",
            rolloutId = "shadow-rca-1",
            maxSignalsPerCase = 8,
        )
        coEvery { audit.publish(any()) } returns Unit
        val evidence = slot<CaseSignalEvidence>()
        val query = slot<CaseCollaborationPolicyQuery>()

        val result = service.authorize("case-1", "rca-investigator", "case.contribute")

        assertThat(result).isInstanceOf(CaseSignalAuthorizationResult.Authorized::class.java)
        verify { policy.decide(capture(query)) }
        assertThat(query.captured.caseClass).isEqualTo("INCIDENT_RESPONSE")
        assertThat(query.captured.deliveryMode).isEqualTo("SHADOW")
        verify { repository.tryRecordAuthorized(capture(evidence), 8) }
        assertThat(evidence.captured.stage).isEqualTo(CaseSignalEvidenceStage.AUTHORIZED)
        assertThat(evidence.captured.policyDecisionId).isEqualTo("opa-decision-7")
        coVerify {
            audit.publish(
                match<AuditEvent> {
                    it.operation == "case.contribute" &&
                        it.resourceId == "case-1" &&
                        it.result == AuditResult.SUCCESS &&
                        it.payload["policy_decision_id"] == "opa-decision-7"
                },
            )
        }
    }

    @Test
    fun `policy allow still denies when local charter gate disagrees`(): Unit = runBlocking {
        every { repository.findContext(any()) } returns CaseAuthorizationContext("INCIDENT_RESPONSE", "SHADOW")
        every { localGate.canJoinCase(any()) } returns false
        every { policy.decide(any()) } returns CaseCollaborationPolicyDecision(
            allow = true,
            reason = "allowed by charter and rules matrix",
            decisionId = "opa-decision-8",
            rolloutId = "shadow-rca-1",
            maxSignalsPerCase = 8,
        )
        coEvery { audit.publish(any()) } returns Unit

        val result = service.authorize("case-1", "rca-investigator", "case.join")

        assertThat(result).isEqualTo(CaseSignalAuthorizationResult.Denied)
        verify {
            repository.record(
                match { it.stage == CaseSignalEvidenceStage.DENIED && it.policyReason == "local charter gate denied" },
            )
        }
    }
}
