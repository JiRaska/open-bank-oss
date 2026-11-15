// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.`in`.CreateProposalUseCase
import com.openbank.agent.application.port.out.DownstreamReadPort
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `aml_propose_case_decision`: an agent triages an AML alert (typically a fuzzy name-only
 * SANCTIONS_HIT) and QUEUES a decision for a human. It must never decide the case itself — the
 * registry has no AML write client at all — and it must refuse a proposal the live case cannot take.
 */
class AmlDecisionProposalToolTest {

    private val mapper = ObjectMapper()
    private val registry = McpToolRegistry()
    private val proposals = mockk<CreateProposalUseCase>()
    private val caseId = UUID.randomUUID().toString()

    private fun charters(): CharterRegistry {
        val config = mockk<CharterConfig>()
        every { config.charters() } returns emptyList()
        return CharterRegistry().also { it.config = config }
    }

    private fun liveCase(status: String): DownstreamReadPort = mockk {
        every { handles(any()) } answers { firstArg<String>().startsWith("aml_get") }
        every { read("aml_get_case", any()) } returns mapper.createObjectNode()
            .put("id", caseId)
            .put("status", status)
            .put("alertCode", "SANCTIONS_HIT")
            .put("alertDetail", "score=0.64")
            .put("matchedEntity", "Example Listed Person")
    }

    @BeforeEach
    fun setUp() {
        registry.objectMapper = mapper
        registry.charters = charters()
        registry.proposals = proposals
        registry.auditPublisher = object : AuditEventPublisher {
            override suspend fun publish(event: AuditEvent) = Unit
        }
        every {
            proposals.create(any(), any(), any(), any(), any(), any())
        } answers {
            AgentProposal(
                id = UUID.randomUUID(), title = firstArg(), rationale = secondArg(),
                suggestedAction = thirdArg(), proposedBy = arg(3), proposedAt = Instant.now(),
                state = ProposalState.PROPOSED, decidedBy = null, decidedAt = null,
                decisionReason = null, modelId = null, correlationId = null,
            )
        }
    }

    private fun call(args: JsonNode) = registry.call("aml_propose_case_decision", args, actorId = "compliance-officer")

    private fun args(
        target: String = "CLEARED",
        rationale: String = "Name-only fuzzy hit; DOB and nationality differ.",
    ) = mapper.createObjectNode().put("caseId", caseId).put("targetStatus", target).put("rationale", rationale)

    @Test
    fun `is a write-proposal capability and is registered`() {
        assertThat(registry.capabilityOf("aml_propose_case_decision")).isEqualTo("propose.aml.decision")
        assertThat(registry.tools.map { it.name }).contains("aml_propose_case_decision")
        assertThat(registry.serviceOf("aml_propose_case_decision")).isEqualTo("aml-service")
    }

    @Test
    fun `an open case yields a HITL proposal and nothing else`() {
        registry.downstream = liveCase("OPEN")

        val result = call(args())

        assertThat(result.isError).isFalse()
        val json = mapper.readTree(result.content.single().text)
        assertThat(json["status"].asText()).isEqualTo("proposed")
        assertThat(json["currentStatus"].asText()).isEqualTo("OPEN")
        assertThat(json["targetStatus"].asText()).isEqualTo("CLEARED")
        verify(exactly = 1) {
            proposals.create(
                match { it.contains(caseId) && it.contains("CLEARED") },
                "Name-only fuzzy hit; DOB and nationality differ.",
                match { it.contains("Example Listed Person") && it.contains("own identity") },
                "compliance-officer",
                any(),
                any(),
            )
        }
    }

    @Test
    fun `a short rationale is refused before any proposal`() {
        registry.downstream = liveCase("OPEN")
        val result = call(args(rationale = "fp"))
        assertThat(result.isError).isTrue()
        assertThat(result.content.single().text).contains("rationale")
        verify(exactly = 0) { proposals.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown target status is refused`() {
        registry.downstream = liveCase("OPEN")
        assertThat(call(args(target = "SAR_FILED")).isError).isTrue()
        verify(exactly = 0) { proposals.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an already closed case is refused`() {
        registry.downstream = liveCase("CLEARED")
        val result = call(args(target = "BLOCKED"))
        assertThat(result.isError).isTrue()
        assertThat(result.content.single().text).contains("already closed")
        verify(exactly = 0) { proposals.create(any(), any(), any(), any(), any(), any()) }
    }
}
