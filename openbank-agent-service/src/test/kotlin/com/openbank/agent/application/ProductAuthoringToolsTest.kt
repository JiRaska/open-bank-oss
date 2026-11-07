// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.`in`.CreateProposalUseCase
import com.openbank.agent.domain.proposal.AgentProposal
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.products.catalog.ReferenceProducts
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** ADR-0352 §12: the MCP product tools — read-only or proposal-only, and no publish/sign/approve tool exists. */
class ProductAuthoringToolsTest {

    private val mapper = ObjectMapper()
    private val audit = object : AuditEventPublisher {
        val events = mutableListOf<AuditEvent>()
        override suspend fun publish(event: AuditEvent) {
            events.add(event)
        }
    }
    private val proposals = mockk<CreateProposalUseCase>()
    private val registry = McpToolRegistry().also {
        it.objectMapper = mapper
        it.auditPublisher = audit
        it.proposals = proposals
        it.charters = CharterRegistry().also { c ->
            c.config = mockk { every { charters() } returns emptyList() }
        }
    }
    private val ir = ReferenceProducts.savingsFixed.canonicalJson()

    private fun call(tool: String, vararg fields: Pair<String, String>) = registry.call(
        tool,
        mapper.createObjectNode().apply {
            fields.forEach { (k, v) -> put(k, v) }
        },
        "product-author",
    )

    @Test
    fun `no registered MCP tool name carries publish, sign or approve`() {
        val offending = registry.tools.map {
            it.name
        }.filter { ProductAuthoringTools.FORBIDDEN_VERBS.containsMatchIn(it) }
        assertThat(offending).isEmpty()
        // Negative control: the matcher does catch the names it exists to keep out.
        assertThat(
            listOf("product_publish", "product.sign", "approve_revision", "sign").all {
                ProductAuthoringTools.FORBIDDEN_VERBS.containsMatchIn(it)
            },
        ).isTrue()
        // Whole-segment match: a verb inside another word is not a hit.
        assertThat(
            listOf("assign_owner", "design_review", "unpublished_list").none {
                ProductAuthoringTools.FORBIDDEN_VERBS.containsMatchIn(it)
            },
        ).isTrue()
        assertThat(registry.capabilityOf("product_publish")).isNull()
        assertThat(registry.capabilityOf("product_sign")).isNull()
    }

    @Test
    fun `the five product tools are registered, capability-mapped and grouped under agent-service`() {
        val names = registry.tools.map { it.name }.filter { it.startsWith("product_") }
        assertThat(names).containsExactlyInAnyOrder(
            "product_lint",
            "product_compile",
            "product_simulate",
            "product_explain",
            "product_propose",
        )
        names.forEach {
            assertThat(registry.capabilityOf(it)).startsWith("product.")
            assertThat(registry.serviceOf(it)).isEqualTo("agent-service")
        }
    }

    @Test
    fun `lint rejects a broken document as data and a missing argument as invalid parameters`() {
        val broken = call("product_lint", "ir" to ir.replace("\"ACT_365_FIXED\"", "\"ACT_999\""))
        // A failed lint is an MCP error, exactly where `obp lint` exits 1.
        assertThat(broken.isError).isTrue()
        assertThat(mapper.readTree(broken.content.first().text)["ok"].asBoolean()).isFalse()

        val missing = call("product_lint")
        assertThat(missing.isError).isTrue()
        assertThat(missing.content.first().text).contains("Invalid parameters", "'ir'")

        val oversized = call("product_lint", "ir" to " ".repeat(ProductAuthoringTools.MAX_DOCUMENT_CHARS + 1))
        assertThat(oversized.isError).isTrue()

        val ok = call("product_lint", "ir" to ir)
        assertThat(mapper.readTree(ok.content.first().text)["revisionHash"].asText())
            .isEqualTo(ReferenceProducts.savingsFixed.compile().revisionHash)
        assertThat(audit.events.map { it.result }).contains(AuditResult.SUCCESS, AuditResult.FAILURE)
    }

    @Test
    fun `compile returns canonical bytes and hash, simulate replays and explain maps a rule to its IR path`() {
        val compiled = mapper.readTree(call("product_compile", "ir" to ir).content.first().text)
        assertThat(compiled["revisionHash"].asText()).isEqualTo(ReferenceProducts.savingsFixed.compile().revisionHash)

        val portfolio = Files.readString(Path.of("..", "openbank-products", "portfolios", "savings-sample.json"))
        val sim = call("product_simulate", "ir" to ir, "portfolio" to portfolio)
        assertThat(sim.isError).isFalse()
        assertThat(sim.content.first().text).contains("\"ruleId\":\"scheduled.fee\"")
        val empty = call("product_simulate", "ir" to ir, "portfolio" to portfolio, "from" to "2030-01-01T00:00:00Z")
        assertThat(empty.content.first().text).doesNotContain("\"ruleId\"")
        assertThat(call("product_simulate", "ir" to ir, "portfolio" to portfolio, "from" to "yesterday").isError)
            .isTrue()

        val explained = mapper.readTree(
            call("product_explain", "ir" to ir, "ruleId" to "scheduled.fee").content.first().text,
        )
        assertThat(explained["ok"].asBoolean()).isTrue()
        assertThat(explained["irPath"].asText()).startsWith("/")
        assertThat(call("product_explain", "ir" to ir).isError).isTrue()
        assertThat(call("product_explain", "ir" to ir, "ruleId" to "no.such.rule").isError).isTrue()
    }

    @Test
    fun `propose records a HITL proposal carrying the draft plan and writes nothing to the catalog`() {
        val action = slot<String>()
        every {
            proposals.create(any(), any(), capture(action), any(), any(), any(), any())
        } returns AgentProposal(
            id = UUID.randomUUID(), title = "t", rationale = "r", suggestedAction = "s",
            proposedBy = "product-author", proposedAt = Instant.now(), state = ProposalState.PROPOSED,
            decidedBy = null, decidedAt = null, decisionReason = null, modelId = null, correlationId = null,
        )
        val bad = call(
            "product_propose",
            "ir" to ir,
            "offeringId" to "not-a-uuid",
            "schemaId" to "deposit",
            "schemaVersion" to "1",
            "rationale" to "x",
        )
        assertThat(bad.isError).isTrue()
        verify(exactly = 0) { proposals.create(any(), any(), any(), any(), any(), any(), any()) }

        val result = call(
            "product_propose",
            "ir" to ir,
            "offeringId" to "00000000-0000-0000-0000-0000000000a1",
            "schemaId" to "deposit",
            "schemaVersion" to "1",
            "rationale" to "rate review",
        )
        assertThat(result.isError).isFalse()
        val body = mapper.readTree(result.content.first().text)
        assertThat(body["status"].asText()).isEqualTo("proposed")
        assertThat(body["publishes"].asBoolean()).isFalse()
        assertThat(action.captured).contains("\"action\":\"create-draft-revision\"", "\"publishes\":false")
    }
}
