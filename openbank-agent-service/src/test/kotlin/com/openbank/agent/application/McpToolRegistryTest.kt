// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.`in`.CreateProposalUseCase
import com.openbank.agent.application.port.out.DownstreamReadPort
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpToolRegistryTest {

    private val registry = McpToolRegistry()

    /**
     * Charter stub for AI attribution (ADR-0031 D5, #3667). McpToolRegistry derives the acting
     * model id from the charter, so every fixture must supply one or the audit event cannot say
     * which model acted.
     */
    private fun stubCharters(model: String = "llama-3.3-70b-versatile"): CharterRegistry {
        val config = mockk<CharterConfig>()
        every { config.charters() } returns listOf(
            mockk<CharterConfig.CharterEntry> {
                every { agentId() } returns "ui-assistant"
                every { this@mockk.model() } returns model
                every { tokensPerRun() } returns Long.MAX_VALUE
                every { runsPerDay() } returns Long.MAX_VALUE
                every { allowedCapabilities() } returns emptyList()
                every { enabled() } returns true
            },
        )
        return CharterRegistry().also { it.config = config }
    }

    /** Captures audit events in-memory so the AI-attribution (D5) can be asserted without Kafka. */
    private class CapturingAuditPublisher : AuditEventPublisher {
        val events = mutableListOf<AuditEvent>()
        override suspend fun publish(event: AuditEvent) {
            events.add(event)
        }
    }

    @Test
    fun `every registered tool maps to a charter capability`() {
        val unmapped = registry.tools.map { it.name }.filter { registry.capabilityOf(it) == null }
        assertThat(unmapped).isEmpty()
    }

    @Test
    fun `every registered tool maps to a service and domain`() {
        // #744: the admin-ui coverage grid groups tools by service, so a tool with no service
        // mapping would be invisible in the grid. Keep services/domains in lock-step with tools.
        val noService = registry.tools.map { it.name }.filter { registry.serviceOf(it) == null }
        val noDomain = registry.tools.map { it.name }.filter { registry.domainOf(it) == null }
        assertThat(noService).isEmpty()
        assertThat(noDomain).isEmpty()
    }

    @Test
    fun `serviceOf maps verb-first core-banking tools to their service`() {
        assertThat(registry.serviceOf("get_account")).isEqualTo("account-service")
        assertThat(registry.serviceOf("get_account_balance")).isEqualTo("balance-service")
        assertThat(registry.serviceOf("list_transactions")).isEqualTo("transaction-service")
        assertThat(registry.serviceOf("list_products")).isEqualTo("product-catalog")
        assertThat(registry.serviceOf("get_catalog_revision")).isEqualTo("product-catalog")
        assertThat(registry.serviceOf("list_ledger_journals")).isEqualTo("ledger-service")
        assertThat(registry.domainOf("get_account")).isEqualTo("Core Banking")
    }

    @Test
    fun `read tools map to the read-only ledger capability`() {
        assertThat(registry.capabilityOf("get_account")).isEqualTo("query.ledger.readonly")
        assertThat(registry.capabilityOf("list_transactions")).isEqualTo("query.ledger.readonly")
        assertThat(registry.capabilityOf("get_balance_holds")).isEqualTo("query.ledger.readonly")
    }

    @Test
    fun `catalog revision review is a read-only catalog capability`() {
        assertThat(registry.capabilityOf("get_catalog_revision")).isEqualTo("query.catalog.readonly")
        assertThat(registry.tools.single { it.name == "get_catalog_revision" }.description)
            .contains("Read-only")
            .contains("cannot create")
    }

    @Test
    fun `an unknown tool has no capability`() {
        assertThat(registry.capabilityOf("delete_everything")).isNull()
    }

    @Test
    fun `GL aggregate tools map to query gl readonly capability`() {
        assertThat(registry.capabilityOf("get_trial_balance")).isEqualTo("query.gl.readonly")
        // Journal posting/listing stays on query.ledger.readonly (backward compat)
        assertThat(registry.capabilityOf("list_ledger_journals")).isEqualTo("query.ledger.readonly")
    }

    @Test
    fun `extended read tools map to their domain read-only capability`() {
        assertThat(registry.capabilityOf("aml_list_cases")).isEqualTo("query.compliance.readonly")
        assertThat(registry.capabilityOf("sanctions_get_check")).isEqualTo("query.compliance.readonly")
        assertThat(registry.capabilityOf("fx_get_rate")).isEqualTo("query.payments.readonly")
        assertThat(registry.capabilityOf("clearing_list_batches")).isEqualTo("query.payments.readonly")
        assertThat(registry.capabilityOf("sepa_instant_list")).isEqualTo("query.payments.readonly")
        assertThat(registry.capabilityOf("interest_get_accruals")).isEqualTo("query.interest.readonly")
        assertThat(registry.capabilityOf("dispute_get")).isEqualTo("query.disputes.readonly")
    }

    @Test
    fun `observability tools map to the observability read-only capability`() {
        assertThat(registry.capabilityOf("query_metrics")).isEqualTo("query.observability.readonly")
        assertThat(registry.capabilityOf("query_loki_logs")).isEqualTo("query.observability.readonly")
        assertThat(registry.capabilityOf("list_alerts")).isEqualTo("query.observability.readonly")
    }

    @Test
    fun `extended read tools never grant a write or money capability`() {
        val newTools = registry.tools.map { it.name }.filter {
            it.startsWith("aml_") ||
                it.startsWith("sanctions_") ||
                it.startsWith("fx_") ||
                it.startsWith("clearing_") ||
                it.startsWith("interest_") ||
                it.startsWith("dispute_") ||
                it.startsWith("sepa_instant_")
        }
        assertThat(newTools).isNotEmpty()
        newTools.forEach { tool ->
            val cap = registry.capabilityOf(tool)
            assertThat(cap).isNotNull()
            assertThat(cap).endsWith(".readonly")
        }
    }

    @Test
    fun `draft_ticket maps to the draft-ticket write-proposal capability`() {
        assertThat(registry.capabilityOf("draft_ticket")).isEqualTo("draft.ticket")
    }

    @Test
    fun `write-capability tools are exactly the expected set`() {
        // Only draft_ticket (draft.ticket) and flip_feature_flag (flags.write) carry write
        // capabilities — every addition must be deliberate and reviewed (ADR-0031 D2).
        // Read-only tools carry capabilities ending in ".readonly" OR starting with "read."
        // (the catalog uses "read.catalog" by convention).
        val writeCaps = registry.tools.map { it.name }
            .filter { tool ->
                val cap = registry.capabilityOf(tool) ?: return@filter false
                cap.contains("write") || cap == "draft.ticket"
            }
        assertThat(writeCaps).containsExactlyInAnyOrder("draft_ticket", "flip_feature_flag")
    }

    @Test
    fun `flip_feature_flag maps to flags-write capability`() {
        assertThat(registry.capabilityOf("flip_feature_flag")).isEqualTo("flags.write")
    }

    @Test
    fun `flip_feature_flag rejects prohibited safety-control keys`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        val proposalSvc = mockk<CreateProposalUseCase>()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.charters = stubCharters()
        registry.proposals = proposalSvc

        val prohibitedKeys = listOf(
            "sca-enforcement-disabled",
            "sanctions-screening-disabled",
            "aml-screening-disabled",
            "payment-gate-fail-open",
        )
        for (key in prohibitedKeys) {
            val args = mapper.createObjectNode()
                .put("flagKey", key)
                .put("targetVariant", "on")
                .put("rationale", "test")
            val result = registry.call("flip_feature_flag", args, actorId = "operator")
            assertThat(result.isError)
                .describedAs("Expected error for prohibited key '$key'")
                .isTrue()
            assertThat(result.content.first().text).contains("prohibited")
        }
        // One FAILURE audit event per rejected flip
        assertThat(audit.events).hasSize(prohibitedKeys.size)
        audit.events.forEach { e ->
            assertThat(e.operation).isEqualTo("featureflag.flip")
            assertThat(e.result).isEqualTo(AuditResult.FAILURE)
            assertThat(e.payload["reason"]).isEqualTo("prohibited")
        }
    }

    @Test
    fun `flip_feature_flag creates a proposal and emits featureflag-flip audit event`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        val proposalSvc = mockk<CreateProposalUseCase>()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.charters = stubCharters()
        registry.proposals = proposalSvc

        val proposalId = java.util.UUID.randomUUID()
        val fakeProposal = com.openbank.agent.domain.proposal.AgentProposal(
            id = proposalId,
            title = "Feature-flag flip: instant-payments-enabled → on",
            rationale = "Enable SEPA instant for pilot cohort",
            suggestedAction = "Update ConfigMap",
            proposedBy = "compliance-officer",
            proposedAt = java.time.Instant.now(),
            state = com.openbank.agent.domain.proposal.ProposalState.PROPOSED,
            decidedBy = null, decidedAt = null, decisionReason = null,
            modelId = null, correlationId = null,
        )
        every {
            proposalSvc.create(
                title = any(),
                rationale = any(),
                suggestedAction = any(),
                proposedBy = any(),
                modelId = any(),
                correlationId = any(),
            )
        } returns fakeProposal

        val args = mapper.createObjectNode()
            .put("flagKey", "instant-payments-enabled")
            .put("targetVariant", "on")
            .put("rationale", "Enable SEPA instant for pilot cohort")
        val result = registry.call("flip_feature_flag", args, actorId = "compliance-officer")

        assertThat(result.isError).isFalse()
        val json = mapper.readTree(result.content.first().text)
        assertThat(json["status"].asText()).isEqualTo("proposed")
        assertThat(json["flagKey"].asText()).isEqualTo("instant-payments-enabled")
        assertThat(json["targetVariant"].asText()).isEqualTo("on")
        assertThat(json["proposalId"].asText()).isEqualTo(proposalId.toString())

        // Audit trail must carry operation=featureflag.flip (ADR-0067 §5, issue #419)
        // plus the normal agent.mcp.tool_exec success event.
        val flipEvent = audit.events.find { it.operation == "featureflag.flip" }
        assertThat(flipEvent).isNotNull
        assertThat(flipEvent!!.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(flipEvent.resourceId).isEqualTo("instant-payments-enabled")
        assertThat(flipEvent.payload["targetVariant"]).isEqualTo("on")
        assertThat(flipEvent.payload["proposalId"]).isEqualTo(proposalId.toString())
    }

    /**
     * A [DownstreamReadPort] stub that serves exactly [tool]. The registry's own job is the
     * governance envelope — capability lookup, audit, error classification — so the transport is a
     * stub here; which downstream service a tool reaches is `RestDownstreamReadAdapterTest`'s job.
     */
    private fun downstream(tool: String, result: JsonNode? = null, failWith: Exception? = null): DownstreamReadPort =
        mockk {
            every { handles(any()) } answers { firstArg<String>() == tool }
            if (failWith != null) {
                every { read(tool, any()) } throws failWith
            } else {
                every { read(tool, any()) } returns result!!
            }
        }

    @Test
    fun `successful tool execution emits an AI-attributed audit event carrying the actor`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.charters = stubCharters()
        registry.downstream = downstream("get_account", mapper.createObjectNode().put("id", "acc-1"))

        val args = mapper.createObjectNode().put("accountId", "acc-1")
        val result = registry.call("get_account", args, actorId = "compliance-officer")

        assertThat(result.isError).isFalse()
        assertThat(audit.events).hasSize(1)
        val event = audit.events.single()
        assertThat(event.actorId).isEqualTo("compliance-officer")
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.operation).isEqualTo("agent.mcp.tool_exec")
        assertThat(event.resourceId).isEqualTo("get_account")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `failed tool execution emits a FAILURE audit event`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.charters = stubCharters()
        registry.downstream = downstream("get_account", failWith = RuntimeException("downstream 503"))

        val args = mapper.createObjectNode().put("accountId", "acc-1")
        val result = registry.call("get_account", args, actorId = "compliance-officer")

        assertThat(result.isError).isTrue()
        val event = audit.events.single()
        assertThat(event.operation).isEqualTo("agent.mcp.tool_exec")
        assertThat(event.result).isEqualTo(AuditResult.FAILURE)
        assertThat(event.payload["error"]).isEqualTo("execution_error")
    }

    @Test
    fun `a missing required argument is audited as invalid_params, not an execution error`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.charters = stubCharters()
        registry.downstream = downstream(
            "get_account",
            failWith = IllegalArgumentException("Required field 'accountId' is missing or blank"),
        )

        val result = registry.call("get_account", mapper.createObjectNode(), actorId = "compliance-officer")

        assertThat(result.isError).isTrue()
        assertThat(result.content.first().text).contains("Invalid parameters")
        assertThat(audit.events.single().payload["error"]).isEqualTo("invalid_params")
    }

    @Test
    fun `a read tool the port does not serve fails closed as an unknown tool`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.charters = stubCharters()
        // The port serves get_account only — a registered tool it cannot reach must not silently
        // resolve to some other service, and must never be reported as a success.
        registry.downstream = downstream("get_account", mapper.createObjectNode())

        val result = registry.call("list_alerts", mapper.createObjectNode(), actorId = "compliance-officer")

        assertThat(result.isError).isTrue()
        assertThat(result.content.first().text).contains("Unknown tool: list_alerts")
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `every read tool is handed to the downstream port with the raw arguments`() {
        val mapper = ObjectMapper()
        // The registry must not reinterpret arguments on the way out — capping, defaulting and
        // marshalling belong to the adapter, so whatever the model sent must arrive verbatim.
        for (tool in listOf("query_metrics", "query_loki_logs", "list_alerts", "list_transactions")) {
            val audit = CapturingAuditPublisher()
            val port = downstream(tool, mapper.createObjectNode().put("status", "success"))
            registry.objectMapper = mapper
            registry.auditPublisher = audit
            registry.charters = stubCharters()
            registry.downstream = port

            val args = mapper.createObjectNode().put("limit", 99999).put("query", "up")
            val result = registry.call(tool, args, actorId = "compliance-officer")

            assertThat(result.isError).describedAs(tool).isFalse()
            io.mockk.verify { port.read(tool, args) }
            assertThat(audit.events.single().resourceId).isEqualTo(tool)
        }
    }
}
