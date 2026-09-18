// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
import com.openbank.context.domain.ContextNode
import com.openbank.context.domain.DataClassification
import com.openbank.context.domain.ImpactProjectionStatus
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.PolicyDecisionPoint
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ContextQueryServiceTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val graph = mockk<ContextGraphPort>()
    private val assignments = mockk<CaseAssignmentPort>()
    private val audit = mockk<ContextReadAuditPort> {
        coEvery { record(any()) } returns UUID(0, 1)
    }
    private val pdp = mockk<PolicyDecisionPoint>()
    private val service = ContextQueryService(
        graph,
        assignments,
        audit,
        pdp,
        Clock.fixed(now, ZoneOffset.UTC),
        SimpleMeterRegistry(),
        100,
        200,
        "openbank-cz",
    )
    private val actor = Investigator("operator-7", listOf("ROLE_COMPLIANCE"))
    private val context = InvestigationContext("case-42", "PAYMENT_COMPLAINT", now)

    @Test
    fun `missing assignment denies before policy and graph lookup`(): Unit = runBlocking {
        coEvery { assignments.isAssigned(actor.id, context.caseId, context.purpose, now) } returns false

        assertThatThrownBy { runBlocking { service.complaint("cmp-1", actor, context) } }
            .isInstanceOf(ContextAccessDenied::class.java)
        coVerify(exactly = 0) { pdp.allow(any()) }
        coVerify(exactly = 0) { graph.neighborhood(any(), any(), any(), any(), any()) }
        coVerify { audit.record(match { it.decision == "DENIED" && it.reasonCode == "NO_ACTIVE_ASSIGNMENT" }) }
    }

    @Test
    fun `pdp outage is audited and fails closed`(): Unit = runBlocking {
        coEvery { assignments.isAssigned(any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } throws IllegalStateException("offline")

        assertThatThrownBy { runBlocking { service.complaint("cmp-1", actor, context) } }
            .isInstanceOf(ContextAuthorizationUnavailable::class.java)
        coVerify(exactly = 0) { graph.neighborhood(any(), any(), any(), any(), any()) }
        coVerify { audit.record(match { it.decision == "UNAVAILABLE" && it.reasonCode == "PDP_UNAVAILABLE" }) }
    }

    @Test
    fun `authorized complaint query passes verified purpose and fixed bounds`(): Unit = runBlocking {
        val view = ContextNeighborhood("complaint:cmp-1", emptyList(), emptyList(), false)
        coEvery { assignments.isAssigned(any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true, policyVersion = "bundle-9")
        coEvery { graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:cmp-1", now, 100, 200) } returns view

        assertThat(service.complaint("cmp-1", actor, context)).isSameAs(view)
        coVerify {
            pdp.allow(
                match {
                    it.attributes["assignmentVerified"] == true &&
                        it.attributes["purpose"] == "PAYMENT_COMPLAINT" &&
                        it.attributes["bankScope"] == "openbank-cz"
                },
            )
        }
        coVerify { audit.record(match { it.decision == "ALLOWED" && it.policyVersion == "bundle-9" }) }
        coVerify {
            audit.record(
                match {
                    it.decision == "DISCLOSED" &&
                        it.accessAuditId == UUID(0, 1) &&
                        it.queryHash?.length == 64 &&
                        it.evidenceCount == 0 &&
                        it.responseStatus == 200
                },
            )
        }
    }

    @Test
    fun `failed disclosure audit suppresses an otherwise successful read`(): Unit = runBlocking {
        coEvery { assignments.isAssigned(any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true, policyVersion = "bundle-9")
        coEvery { graph.neighborhood(any(), any(), any(), any(), any()) } returns
            ContextNeighborhood("complaint:cmp-1", emptyList(), emptyList(), false)
        coEvery { audit.record(match { it.decision == "DISCLOSED" }) } throws IllegalStateException("audit unavailable")

        assertThatThrownBy { runBlocking { service.complaint("cmp-1", actor, context) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("audit unavailable")
        coVerify { audit.record(match { it.decision == "ALLOWED" }) }
        coVerify { audit.record(match { it.decision == "DISCLOSED" }) }
    }

    @Test
    fun `incident response aggregates types and never returns identifiers`(): Unit = runBlocking {
        coEvery { assignments.isAssigned(any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true)
        coEvery { graph.neighborhood(any(), any(), any(), any(), any()) } returns ContextNeighborhood(
            "incident:inc-1",
            listOf(
                node("incident:inc-1", "INCIDENT"),
                node("payment:p1", "PAYMENT"),
                node("payment:p2", "PAYMENT"),
                node("workflow:w1", "WORKFLOW"),
            ),
            emptyList(),
            false,
        )

        val impact = service.incident("inc-1", actor, context.copy(purpose = "INCIDENT_IMPACT"))
        assertThat(impact.affectedByType).containsEntry("PAYMENT", 2).containsEntry("WORKFLOW", 1)
        assertThat(impact.total).isEqualTo(3)
        assertThat(impact.projectionStatus).isEqualTo(ImpactProjectionStatus.AVAILABLE)
        assertThat(impact.drilldownAvailable).isFalse()
        assertThat(impact.toString()).doesNotContain("payment:p1", "workflow:w1")
    }

    @Test
    fun `missing incident projection reports unknown impact after authorization`(): Unit = runBlocking {
        coEvery { assignments.isAssigned(any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true)
        coEvery { graph.neighborhood(any(), any(), any(), any(), any()) } returns null

        val impact = service.incident("missing", actor, context.copy(purpose = "INCIDENT_IMPACT"))
        assertThat(impact.projectionStatus).isEqualTo(ImpactProjectionStatus.MISSING)
        assertThat(impact.affectedByType).isEmpty()
        coVerify { audit.record(match { it.decision == "ALLOWED" && it.rootRef == "incident:missing" }) }
    }

    @Test
    fun `bounded incident query preserves partial coverage`(): Unit = runBlocking {
        coEvery { assignments.isAssigned(any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true)
        coEvery { graph.neighborhood(any(), any(), any(), any(), any()) } returns ContextNeighborhood(
            "incident:inc-1",
            listOf(node("incident:inc-1", "INCIDENT"), node("service:s1", "SERVICE")),
            emptyList(),
            true,
        )

        val impact = service.incident("inc-1", actor, context.copy(purpose = "INCIDENT_IMPACT"))
        assertThat(impact.projectionStatus).isEqualTo(ImpactProjectionStatus.PARTIAL)
        assertThat(impact.total).isEqualTo(1)
        assertThat(impact.toString()).doesNotContain("service:s1")
    }

    @Test
    fun `fraud evidence requires assignment to the exact root even for admin`(): Unit = runBlocking {
        val admin = Investigator("admin-1", listOf("ROLE_ADMIN"))
        val investigation = InvestigationContext("case-42", "FRAUD_INVESTIGATION", now)
        coEvery {
            assignments.isAssignedToRoot(
                admin.id,
                investigation.caseId,
                investigation.purpose,
                "fraud-case:case-42",
                now,
            )
        } returns false

        assertThatThrownBy {
            runBlocking {
                service.fraudCaseEvidence(
                    "case-42",
                    admin,
                    investigation,
                    summarize = { DisclosureSummary.materialized(emptyList(), 0, false, emptyList()) },
                ) { "secret" }
            }
        }.isInstanceOf(ContextAccessDenied::class.java)
        coVerify(exactly = 0) { pdp.allow(any()) }
        coVerify {
            audit.record(match { it.rootRef == "fraud-case:case-42" && it.decision == "DENIED" })
        }
    }

    @Test
    fun `fraud evidence passes scoped policy and audits before returning`(): Unit = runBlocking {
        val admin = Investigator("admin-1", listOf("ROLE_ADMIN"))
        val investigation = InvestigationContext("case-42", "FRAUD_INVESTIGATION", now)
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true, policyVersion = "fraud-policy-1")

        assertThat(
            service.fraudCaseEvidence(
                "case-42",
                admin,
                investigation,
                summarize = { DisclosureSummary.materialized(emptyList(), 0, false, emptyList()) },
            ) { "allowed" },
        ).isEqualTo("allowed")
        coVerify {
            pdp.allow(
                match {
                    it.action == "context.fraud-case.read" &&
                        it.resource?.id == "fraud-case:case-42" &&
                        it.attributes["rootScopeVerified"] == true &&
                        it.attributes["purpose"] == "FRAUD_INVESTIGATION"
                },
            )
        }
        coVerify { audit.record(match { it.decision == "ALLOWED" && it.policyVersion == "fraud-policy-1" }) }
    }

    private fun node(key: String, type: String) = ContextNode(
        key,
        ContextNamespace.INCIDENT,
        type,
        "synthetic",
        key,
        type,
        DataClassification.INTERNAL,
        now,
        null,
        now,
        1,
    )
}
