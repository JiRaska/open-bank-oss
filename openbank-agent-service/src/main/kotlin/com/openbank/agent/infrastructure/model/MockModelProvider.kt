// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.out.ModelProvider
import com.openbank.agent.domain.model.ChatRole
import com.openbank.agent.domain.model.ModelDescriptor
import com.openbank.agent.domain.model.ModelRequest
import com.openbank.agent.domain.model.ModelResponse
import com.openbank.agent.domain.model.ModelUsage
import com.openbank.agent.domain.model.StopReason
import com.openbank.agent.domain.model.ToolInvocation
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

/**
 * Deterministic, offline stand-in for a real model (demo / sandbox — ADR-0031 D6). It implements
 * the SAME [ModelProvider] port a hosted public API would, so swapping in a real backend is a
 * config entry plus one adapter — this class never has to change.
 *
 * To exercise the full reasoning loop without an LLM it does a tiny bit of pattern matching:
 * if the user names an account id / IBAN and the matching read tool is offered, it emits a
 * tool invocation; once a tool result comes back it summarises it. Otherwise it explains what
 * it can do. No network, no PII leaves the process.
 */
@ApplicationScoped
class MockModelProvider : ModelProvider {

    @Inject
    lateinit var objectMapper: ObjectMapper

    override val key: String = "mock"

    private val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val ibanRegex = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b")

    override suspend fun complete(model: ModelDescriptor, request: ModelRequest): ModelResponse {
        val lastTool = request.messages.lastOrNull { it.role == ChatRole.TOOL }
        val lastUser = request.messages.lastOrNull { it.role == ChatRole.USER }

        // Already got a tool result this turn -> summarise and finish.
        if (request.messages.lastOrNull()?.role == ChatRole.TOOL && lastTool != null) {
            return text(
                model,
                "Here is what I found:\n\n${lastTool.content}\n\n" +
                    "(Mock model — wire a real provider in model-gateway.models to get live reasoning.)",
            )
        }

        val prompt = lastUser?.content.orEmpty()
        val toolNames = request.tools.map { it.name }.toSet()

        // Try to drive a read tool from the prompt.
        ibanRegex.find(prompt)?.value?.let { iban ->
            if ("get_account_by_iban" in toolNames) return invoke("get_account_by_iban", mapOf("iban" to iban), model)
        }
        uuidRegex.find(prompt)?.value?.let { id ->
            val wantsBalance = prompt.containsAny("balance", "zůstatek", "zustatek")
            if (wantsBalance && "get_account_balance" in toolNames) {
                return invoke("get_account_balance", mapOf("accountId" to id), model)
            }
            if (prompt.containsAny("transaction", "transakc") && "list_transactions" in toolNames) {
                return invoke("list_transactions", mapOf("accountId" to id), model)
            }
            if ("get_account" in toolNames) return invoke("get_account", mapOf("accountId" to id), model)
        }

        // Nothing to act on -> describe capabilities.
        val toolList = if (request.tools.isEmpty()) {
            "(no tools offered)"
        } else {
            request.tools.joinToString("\n") { "  • ${it.name} — ${it.description}" }
        }
        return text(
            model,
            "I'm the OpenBank assistant (mock). Give me an account id or IBAN and I can look it up. " +
                "Available read-only tools:\n$toolList",
        )
    }

    private fun text(model: ModelDescriptor, content: String) = ModelResponse(
        content = content,
        stopReason = StopReason.END,
        usage = ModelUsage(inputTokens = 0, outputTokens = content.split(' ').size),
        modelId = model.id,
        modelVersion = "mock-1",
    )

    private fun invoke(tool: String, args: Map<String, String>, model: ModelDescriptor): ModelResponse {
        val node = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(args)
        return ModelResponse(
            content = "",
            toolInvocations = listOf(ToolInvocation(id = UUID.randomUUID().toString(), name = tool, arguments = node)),
            stopReason = StopReason.TOOL_USE,
            usage = ModelUsage(),
            modelId = model.id,
            modelVersion = "mock-1",
        )
    }

    private fun String.containsAny(vararg needles: String) = needles.any { this.contains(it, ignoreCase = true) }
}
