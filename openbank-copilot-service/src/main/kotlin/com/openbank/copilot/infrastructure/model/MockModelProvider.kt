// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ModelProvider
import com.openbank.copilot.domain.model.ChatRole
import com.openbank.copilot.domain.model.ModelDescriptor
import com.openbank.copilot.domain.model.ModelRequest
import com.openbank.copilot.domain.model.ModelResponse
import com.openbank.copilot.domain.model.ModelUsage
import com.openbank.copilot.domain.model.StopReason
import com.openbank.copilot.domain.model.ToolInvocation
import com.openbank.copilot.domain.model.ToolSpec
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Deterministic, offline stand-in for a real model (sandbox — ADR-0089 D6). It implements the SAME
 * [ModelProvider] port a hosted free API or a self-hosted vLLM endpoint would, so swapping in a real
 * backend is a config entry plus one adapter — this class never changes. No network, no PII leaves
 * the process.
 *
 * To exercise the full governed loop without an LLM it does a little pattern matching: a tool result
 * coming back is summarised; an account id + "balance/zůstatek" in the prompt drives the READ tool
 * (only if it is offered). Otherwise it explains what it can do.
 */
@ApplicationScoped
class MockModelProvider(private val objectMapper: ObjectMapper) : ModelProvider {

    override val key: String = "mock"

    override suspend fun completeStream(
        model: ModelDescriptor,
        request: ModelRequest,
        onChunk: suspend (String) -> Unit,
    ): ModelResponse {
        val response = complete(model, request)
        // Simulate word-by-word streaming with a small delay so the UI behaviour is testable in dev.
        if (response.toolInvocations.isEmpty() && response.content.isNotBlank()) {
            response.content.split(Regex("(?<= )")).forEach { word ->
                delay(MOCK_STREAM_DELAY_MS)
                onChunk(word)
            }
        }
        return response
    }

    override suspend fun complete(model: ModelDescriptor, request: ModelRequest): ModelResponse {
        // A tool result just came back → narrate it and finish.
        val last = request.messages.lastOrNull()
        if (last?.role == ChatRole.TOOL) {
            return text(model, "Tady je, co jsem zjistil:\n\n${last.content}")
        }
        val prompt = request.messages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        val toolNames = request.tools.map { it.name }.toSet()
        routeToTool(prompt, toolNames, model)?.let { return it }
        return text(model, capabilities(request.tools))
    }

    /** Mock dispatch: drive a tool from the prompt, or null to fall back to a capabilities message. */
    private fun routeToTool(prompt: String, toolNames: Set<String>, model: ModelDescriptor): ModelResponse? {
        // Prefer the one-step balance tool for any balance/account overview query.
        if (prompt.containsAny(BALANCE_CUES) && "get_my_balances" in toolNames) {
            return invoke("get_my_balances", emptyMap(), model)
        }
        UUID_REGEX.find(prompt)?.value?.let { id ->
            accountTool(prompt, id, toolNames, model)?.let { return it }
        }
        val help = prompt.contains("?") || prompt.containsAny(HELP_CUES)
        if (help && "search_help" in toolNames) {
            return invoke("search_help", mapOf("query" to prompt), model)
        }
        return null
    }

    private fun accountTool(
        prompt: String,
        id: String,
        toolNames: Set<String>,
        model: ModelDescriptor,
    ): ModelResponse? {
        paymentInvocation(prompt, id, toolNames, model)?.let { return it }
        val cardFreeze = prompt.contains("kart", ignoreCase = true) && prompt.containsAny(CARD_FREEZE_CUES)
        if (cardFreeze && "propose_card_freeze" in toolNames) {
            return invoke("propose_card_freeze", mapOf("cardId" to id), model)
        }
        if (prompt.containsAny(DISPUTE_CUES) && "propose_dispute" in toolNames) {
            return invoke("propose_dispute", mapOf("transactionId" to id, "reason" to prompt), model)
        }
        if (prompt.containsAny(TRANSACTION_CUES) && "list_transactions" in toolNames) {
            return invoke("list_transactions", mapOf("accountId" to id), model)
        }
        if (prompt.containsAny(BALANCE_CUES) && "get_account_balance" in toolNames) {
            return invoke("get_account_balance", mapOf("accountId" to id), model)
        }
        return null
    }

    private fun capabilities(tools: List<ToolSpec>): String {
        val toolList = if (tools.isEmpty()) {
            "(zatím bez nástrojů)"
        } else {
            tools.joinToString("\n") { "  • ${it.name} — ${it.description}" }
        }
        return "Jsem bankovní asistent OpenBank (mock). Zadejte id účtu (UUID) a zeptejte se na zůstatek. " +
            "Dostupné nástroje:\n$toolList"
    }

    private fun text(model: ModelDescriptor, content: String) = ModelResponse(
        content = content,
        stopReason = StopReason.END,
        usage = ModelUsage(inputTokens = 0, outputTokens = content.split(' ').size),
        modelId = model.id,
        modelVersion = "mock-1",
    )

    private fun invoke(tool: String, args: Map<String, String>, model: ModelDescriptor): ModelResponse {
        val node = objectMapper.valueToTree<JsonNode>(args)
        return ModelResponse(
            content = "",
            toolInvocations = listOf(ToolInvocation(id = UUID.randomUUID().toString(), name = tool, arguments = node)),
            stopReason = StopReason.TOOL_USE,
            usage = ModelUsage(),
            modelId = model.id,
            modelVersion = "mock-1",
        )
    }

    private fun paymentInvocation(
        prompt: String,
        accountId: String,
        toolNames: Set<String>,
        model: ModelDescriptor,
    ): ModelResponse? {
        if (!prompt.containsAny(PAYMENT_CUES) || "propose_payment" !in toolNames) return null
        val iban = IBAN_REGEX.find(prompt)?.value ?: return null
        // Strip the UUID + IBAN first so the amount regex doesn't latch onto their digits.
        val cleaned = prompt.replace(UUID_REGEX, " ").replace(iban, " ")
        val amount = AMOUNT_REGEX.find(cleaned)?.value ?: return null
        val args = mutableMapOf("fromAccountId" to accountId, "payeeIban" to iban, "amount" to amount)
        CURRENCY_REGEX.find(prompt)?.value?.let { args["currency"] = it }
        return invoke("propose_payment", args, model)
    }

    private fun String.containsAny(cues: List<String>) = cues.any { contains(it, ignoreCase = true) }

    private companion object {
        val UUID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val IBAN_REGEX = Regex("[A-Z]{2}\\d{2}[A-Z0-9]{10,30}")
        val AMOUNT_REGEX = Regex("\\d+(?:[.,]\\d+)?")
        val CURRENCY_REGEX = Regex("\\b(CZK|EUR|USD|GBP)\\b")

        val PAYMENT_CUES =
            listOf("pošli", "posli", "zaplať", "zaplat", "převeď", "preved", "odešli", "send", "pay ")
        val CARD_FREEZE_CUES = listOf("zablok", "zmraz", "freeze", "block")
        val DISPUTE_CUES = listOf("reklam", "spor", "dispute", "neuzná", "neznám", "podvod", "chargeback")
        val BALANCE_CUES = listOf("balance", "zůstatek", "zustatek", "kolik mám", "kolik mam")
        val TRANSACTION_CUES = listOf("transak", "transaction", "pohyb", "platby")
        val HELP_CUES = listOf("jak ", "proč", "co ", "kde ", "how ", "poplat", "kart", "platb", "ztrat", "potvr")
        const val MOCK_STREAM_DELAY_MS = 60L
    }
}
