// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpToolRegistryTest {

    private val registry = McpToolRegistry()

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
        val proposalSvc = mockk<ProposalService>()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.proposalService = proposalSvc

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
        val proposalSvc = mockk<ProposalService>()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.proposalService = proposalSvc

        val proposalId = java.util.UUID.randomUUID()
        val fakeProposal = com.openbank.agent.infrastructure.persistence.AgentProposal(
            id = proposalId,
            title = "Feature-flag flip: instant-payments-enabled → on",
            rationale = "Enable SEPA instant for pilot cohort",
            suggestedAction = "Update ConfigMap",
            proposedBy = "compliance-officer",
            proposedAt = java.time.Instant.now(),
            state = com.openbank.agent.infrastructure.persistence.ProposalState.PROPOSED,
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

    fun `successful tool execution emits an AI-attributed audit event carrying the actor`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.accountClient = mockk()
        every { registry.accountClient.getAccount(any()) } returns mapper.createObjectNode().put("id", "acc-1")

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
        registry.accountClient = mockk()
        every { registry.accountClient.getAccount(any()) } throws RuntimeException("downstream 503")

        val args = mapper.createObjectNode().put("accountId", "acc-1")
        val result = registry.call("get_account", args, actorId = "compliance-officer")

        assertThat(result.isError).isTrue()
        val event = audit.events.single()
        assertThat(event.operation).isEqualTo("agent.mcp.tool_exec")
        assertThat(event.result).isEqualTo(AuditResult.FAILURE)
        assertThat(event.payload["error"]).isEqualTo("execution_error")
    }

    @Test
    fun `query_metrics runs an instant query and emits an AI-attributed audit event`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.prometheusClient = mockk()
        // No start/end → instant query path.
        every { registry.prometheusClient.query("up", null) } returns
            mapper.createObjectNode().put("status", "success")

        val args = mapper.createObjectNode().put("query", "up")
        val result = registry.call("query_metrics", args, actorId = "compliance-officer")

        assertThat(result.isError).isFalse()
        val event = audit.events.single()
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.operation).isEqualTo("agent.mcp.tool_exec")
        assertThat(event.resourceId).isEqualTo("query_metrics")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
    }

    @Test
    fun `query_loki_logs caps the line limit at 1000`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.lokiClient = mockk()
        every {
            registry.lokiClient.queryRange(any(), any(), any(), 1000, any())
        } returns mapper.createObjectNode().put("status", "success")

        val args = mapper.createObjectNode()
            .put("query", "{namespace=\"payments\"}")
            .put("limit", 99999)
        val result = registry.call("query_loki_logs", args, actorId = "compliance-officer")

        assertThat(result.isError).isFalse()
        // Verifies the coerceIn(1, 1000) cap reached the client as 1000.
        io.mockk.verify { registry.lokiClient.queryRange(any(), any(), any(), 1000, any()) }
    }

    @Test
    fun `list_alerts calls alertmanager and emits an AI-attributed audit event`() {
        val mapper = ObjectMapper()
        val audit = CapturingAuditPublisher()
        registry.objectMapper = mapper
        registry.auditPublisher = audit
        registry.alertmanagerClient = mockk()
        // active=true, silenced=false hardcoded; filter is null when the arg is absent.
        every { registry.alertmanagerClient.listAlerts(true, false, null) } returns
            mapper.createArrayNode().add(mapper.createObjectNode().put("status", "firing"))

        val args = mapper.createObjectNode()
        val result = registry.call("list_alerts", args, actorId = "compliance-officer")

        assertThat(result.isError).isFalse()
        val event = audit.events.single()
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.operation).isEqualTo("agent.mcp.tool_exec")
        assertThat(event.resourceId).isEqualTo("list_alerts")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
    }
}
