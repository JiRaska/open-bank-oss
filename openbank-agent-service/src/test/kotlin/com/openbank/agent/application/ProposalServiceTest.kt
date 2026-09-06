// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.out.AgentProposalRepository
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The maker-checker invariants of the HITL proposal lifecycle (ADR-0031 D4/D5): who may decide,
 * what a double decision does, and what the AI-attribution audit payload must always carry.
 */
class ProposalServiceTest {

    private val fixed: Instant = Instant.parse("2026-01-02T03:04:05Z")
    private val clock: Clock = Clock.fixed(fixed, ZoneOffset.UTC)
    private val repository = mockk<AgentProposalRepository>(relaxed = true)
    private val audit = mockk<AuditEventPublisher>(relaxed = true)
    private val service = ProposalService(repository, audit, clock)

    private fun proposal(
        id: UUID = UUID.randomUUID(),
        state: ProposalState = ProposalState.PROPOSED,
        proposedBy: String = "ui-assistant",
    ) = AgentProposal(
        id = id,
        title = "raise the fee cap",
        rationale = "because",
        suggestedAction = "do it",
        proposedBy = proposedBy,
        proposedAt = fixed,
        state = state,
        decidedBy = null,
        decidedAt = null,
        decisionReason = null,
        modelId = null,
        correlationId = null,
    )

    private fun captureAudit(): MutableList<AuditEvent> {
        val events = mutableListOf<AuditEvent>()
        coEvery { audit.publish(capture(events)) } returns Unit
        return events
    }

    @Test
    fun `create stamps PROPOSED, the clock instant and a fresh id, and inserts exactly that row`() {
        val events = captureAudit()
        val inserted = slot<AgentProposal>()
        every { repository.insert(capture(inserted)) } returns Unit

        val row = service.create(
            title = "t",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "control-agent",
            modelId = "llama-3.3",
            correlationId = "corr-1",
            metadata = mapOf("context_hash" to "abc"),
        )

        assertThat(row.state).isEqualTo(ProposalState.PROPOSED)
        assertThat(row.proposedAt).isEqualTo(fixed)
        assertThat(row.decidedBy).isNull()
        assertThat(inserted.captured).isEqualTo(row)
        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.actorType).isEqualTo("AI_AGENT")
            assertThat(e.actorId).isEqualTo("control-agent")
            assertThat(e.operation).isEqualTo("agent.proposal.created")
            assertThat(e.resourceId).isEqualTo(row.id.toString())
            assertThat(e.payload["model_id"]).isEqualTo("llama-3.3")
            assertThat(e.payload["correlation_id"]).isEqualTo("corr-1")
            assertThat(e.payload["context_hash"]).isEqualTo("abc")
            // Rationale may quote data the agent read — it must never reach the audit payload.
            assertThat(e.payload).doesNotContainKey("rationale")
        })
    }

    @Test
    fun `create records model_id 'unknown' rather than omitting the key when the model is unattributed`() {
        val events = captureAudit()

        service.create("t", "r", "a", "control-agent", modelId = null, correlationId = null)

        assertThat(events.single().payload).containsEntry("model_id", CharterRegistry.UNKNOWN_MODEL)
        assertThat(events.single().payload).doesNotContainKey("correlation_id")
    }

    @Test
    fun `create defensively copies the metadata map so a later caller mutation cannot alter the row`() {
        captureAudit()
        val metadata = mutableMapOf("context_hash" to "abc")

        val row = service.create("t", "r", "a", "agent", null, null, metadata)
        metadata["context_hash"] = "tampered"

        assertThat(row.metadata).containsEntry("context_hash", "abc")
    }

    @Test
    fun `decide returns null for an unknown proposal without touching the store or the audit trail`() {
        val events = captureAudit()
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns null

        assertThat(service.decide(id, approve = true, decidedBy = "alice", reason = null)).isNull()
        assertThat(events).isEmpty()
    }

    @Test
    fun `decide rejects self-approval - segregation of duties`() {
        val row = proposal(proposedBy = "alice")
        every { repository.findById(row.id) } returns row

        assertThatThrownBy { service.decide(row.id, true, "alice", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("approver must differ")
    }

    @Test
    fun `decide rejects a blank decider`() {
        val row = proposal()
        every { repository.findById(row.id) } returns row

        assertThatThrownBy { service.decide(row.id, true, "   ", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decide refuses to re-decide a proposal that is already APPROVED`() {
        val row = proposal(state = ProposalState.APPROVED)
        every { repository.findById(row.id) } returns row

        assertThatThrownBy { service.decide(row.id, false, "bob", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("already APPROVED")
    }

    @Test
    fun `decide throws and audits nothing when the conditional update loses the race`() {
        val events = captureAudit()
        val row = proposal()
        every { repository.findById(row.id) } returns row
        every { repository.compareAndSetDecision(row.id, any(), any(), any(), any()) } returns false

        assertThatThrownBy { service.decide(row.id, true, "bob", "ok") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("already decided")
        assertThat(events).isEmpty()
    }

    @Test
    fun `an approval returns the updated aggregate and audits under the deciding HUMAN`() {
        val events = captureAudit()
        val row = proposal(proposedBy = "ui-assistant")
        every { repository.findById(row.id) } returns row
        every { repository.compareAndSetDecision(row.id, any(), any(), any(), any()) } returns true

        val updated = service.decide(row.id, approve = true, decidedBy = "bob", reason = "looks fine")

        assertThat(updated!!.state).isEqualTo(ProposalState.APPROVED)
        assertThat(updated.decidedBy).isEqualTo("bob")
        assertThat(updated.decidedAt).isEqualTo(fixed)
        assertThat(updated.decisionReason).isEqualTo("looks fine")
        val event = events.single()
        assertThat(event.actorType).isEqualTo("HUMAN")
        assertThat(event.actorId).isEqualTo("bob")
        assertThat(event.operation).isEqualTo("agent.proposal.decided")
        assertThat(event.payload).containsEntry("decision", "APPROVED")
        assertThat(event.payload).containsEntry("proposed_by", "ui-assistant")
        assertThat(event.payload).containsEntry("reason", "looks fine")
    }

    @Test
    fun `a rejection stores REJECTED and omits the reason key when none was given`() {
        val events = captureAudit()
        val row = proposal()
        val newState = slot<ProposalState>()
        every { repository.findById(row.id) } returns row
        every { repository.compareAndSetDecision(row.id, capture(newState), any(), any(), any()) } returns true

        val updated = service.decide(row.id, approve = false, decidedBy = "bob", reason = null)

        assertThat(newState.captured).isEqualTo(ProposalState.REJECTED)
        assertThat(updated!!.state).isEqualTo(ProposalState.REJECTED)
        assertThat(events.single().payload).doesNotContainKey("reason")
    }

    @Test
    fun `queries delegate straight to the repository, agent filter included`() {
        val pending = listOf(proposal())
        every { repository.listPending("ui-assistant") } returns pending
        every { repository.listAll(50, null) } returns emptyList()
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns null

        assertThat(service.listPending("ui-assistant")).isEqualTo(pending)
        assertThat(service.listAll(50, null)).isEmpty()
        assertThat(service.get(id)).isNull()
    }
}
