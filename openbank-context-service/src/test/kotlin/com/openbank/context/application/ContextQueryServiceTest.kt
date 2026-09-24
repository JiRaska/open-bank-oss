// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import com.openbank.context.domain.ContextEdge
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

class ContextQueryServiceTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val graph = mockk<ContextGraphPort>()
    private val assignments = mockk<CaseAssignmentPort>()
    private val audit = mockk<ContextReadAuditPort>(relaxed = true)
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
        1,
    )
    private val actor = Investigator("operator-7", listOf("ROLE_COMPLIANCE"))
    private val context = InvestigationContext("case-42", "PAYMENT_COMPLAINT", now)

    @Test
    fun `missing assignment denies before policy and graph lookup`(): Unit = runBlocking {
        coEvery {
            assignments.isAssignedToRoot(actor.id, context.caseId, context.purpose, "complaint:cmp-1", now)
        } returns
            false

        assertThatThrownBy { runBlocking { service.complaint("cmp-1", actor, context) } }
            .isInstanceOf(ContextAccessDenied::class.java)
        coVerify(exactly = 0) { pdp.allow(any()) }
        coVerify(exactly = 0) { graph.neighborhood(any(), any(), any(), any(), any()) }
        coVerify { audit.record(match { it.decision == "DENIED" && it.reasonCode == "NO_ACTIVE_ASSIGNMENT" }) }
    }

    @Test
    fun `a complaint assignment cannot read a different complaint root`(): Unit = runBlocking {
        coEvery {
            assignments.isAssignedToRoot(actor.id, context.caseId, context.purpose, "complaint:other", now)
        } returns
            false

        assertThatThrownBy { runBlocking { service.complaint("other", actor, context) } }
            .isInstanceOf(ContextAccessDenied::class.java)
        coVerify(exactly = 0) { pdp.allow(any()) }
        coVerify(exactly = 0) { graph.neighborhood(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pdp outage is audited and fails closed`(): Unit = runBlocking {
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } throws IllegalStateException("offline")

        assertThatThrownBy { runBlocking { service.complaint("cmp-1", actor, context) } }
            .isInstanceOf(ContextAuthorizationUnavailable::class.java)
        coVerify(exactly = 0) { graph.neighborhood(any(), any(), any(), any(), any()) }
        coVerify { audit.record(match { it.decision == "UNAVAILABLE" && it.reasonCode == "PDP_UNAVAILABLE" }) }
    }

    @Test
    fun `authorized complaint query passes verified purpose and fixed bounds`(): Unit = runBlocking {
        val view = ContextNeighborhood("complaint:cmp-1", emptyList(), emptyList(), false)
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
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
            audit.recordDisclosure(
                match {
                    it.disclosure.evidenceCount == 0 && it.disclosure.projectionGeneration == 1L
                },
            )
        }
    }

    @Test
    fun `failed disclosure write suppresses an otherwise successful graph response`(): Unit = runBlocking {
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true)
        coEvery { graph.neighborhood(any(), any(), any(), any(), any()) } returns
            ContextNeighborhood("complaint:cmp-1", emptyList(), emptyList(), false)
        coEvery { audit.recordDisclosure(any()) } throws IllegalStateException("audit unavailable")

        assertThatThrownBy { runBlocking { service.complaint("cmp-1", actor, context) } }
            .isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 1) { audit.record(match { it.decision == "ALLOWED" }) }
        coVerify(exactly = 1) { audit.recordDisclosure(any()) }
    }

    @Test
    fun `failed graph query never records disclosure`(): Unit = runBlocking {
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true)
        coEvery {
            graph.neighborhood(any(), any(), any(), any(), any())
        } throws IllegalStateException("projection unavailable")

        assertThatThrownBy { runBlocking { service.complaint("cmp-1", actor, context) } }
            .isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 0) { audit.recordDisclosure(any()) }
    }

    @Test
    fun `incident response aggregates reported services and audits their source event`(): Unit = runBlocking {
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true)
        coEvery { graph.neighborhood(any(), any(), any(), any(), any()) } returns ContextNeighborhood(
            "incident:inc-1",
            listOf(
                node("incident:inc-1", "INCIDENT"),
                node("service:ledger-service", "SERVICE"),
            ),
            listOf(
                ContextEdge(
                    "edge-1", ContextNamespace.INCIDENT, "incident:inc-1", "service:ledger-service",
                    "AFFECTS_SERVICE", "incident:inc-1:ICT_INCIDENT_REPORTED:1", now, null, now, 1,
                ),
            ),
            false,
        )

        val impact = service.incident("inc-1", actor, context.copy(purpose = "INCIDENT_IMPACT"))
        assertThat(impact.affectedByType).containsEntry("SERVICE", 1)
        assertThat(impact.total).isEqualTo(1)
        assertThat(impact.projectionStatus).isEqualTo(ImpactProjectionStatus.AVAILABLE)
        assertThat(impact.drilldownAvailable).isFalse()
        assertThat(impact.toString()).doesNotContain("ledger-service", "incident:inc-1:ICT_INCIDENT_REPORTED:1")
        coVerify {
            audit.recordDisclosure(
                match {
                    it.disclosure.evidenceRefs == listOf("incident:inc-1:ICT_INCIDENT_REPORTED:1") &&
                        it.disclosure.evidenceCount == 1
                },
            )
        }
    }

    @Test
    fun `missing incident projection reports unknown impact after authorization`(): Unit = runBlocking {
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
        coEvery { pdp.allow(any()) } returns AuthzDecision(true)
        coEvery { graph.neighborhood(any(), any(), any(), any(), any()) } returns null

        val impact = service.incident("missing", actor, context.copy(purpose = "INCIDENT_IMPACT"))
        assertThat(impact.projectionStatus).isEqualTo(ImpactProjectionStatus.MISSING)
        assertThat(impact.affectedByType).isEmpty()
        coVerify { audit.record(match { it.decision == "ALLOWED" && it.rootRef == "incident:missing" }) }
        coVerify { audit.recordDisclosure(match { it.disclosure.evidenceCount == 0 }) }
    }

    @Test
    fun `bounded incident query preserves partial coverage`(): Unit = runBlocking {
        coEvery { assignments.isAssignedToRoot(any(), any(), any(), any(), any()) } returns true
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
        coVerify { audit.recordDisclosure(match { it.disclosure.evidenceCount == 1 && it.disclosure.truncated }) }
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
