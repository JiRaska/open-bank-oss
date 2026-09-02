// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.copilot.application.HybridHelpRetrieval
import com.openbank.copilot.application.port.out.CopilotTool
import com.openbank.copilot.application.port.out.ToolResult
import jakarta.enterprise.context.ApplicationScoped

/**
 * RAG-as-a-tool (ADR-0089 D4): hybrid keyword + semantic search over the bundled customer-help
 * corpus. The model calls this for "how do I…" questions and narrates the returned passages WITH
 * their citations, instead of inventing an answer. No customer data reaches it.
 *
 * Retrieval is no longer purely in-process (ADR-0183 / ADR-0265 slice 4): the semantic half embeds
 * the query through the gateway and queries pgvector. It degrades to the original keyword-only
 * behaviour whenever either is unavailable, and [HybridHelpRetrieval] counts that it did.
 */
@ApplicationScoped
class HelpSearchTool(private val retrieval: HybridHelpRetrieval) : CopilotTool {

    override val name = "search_help"
    override val description = "Search the bank's help/FAQ for a how-to answer; returns passages with citations."
    override val capability = "help.search.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf("query" to mapOf("type" to "string", "description" to "The customer's question")),
        "required" to listOf("query"),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val query = arguments.get("query")?.asText()?.takeIf { it.isNotBlank() }
            ?: return ToolResult("Missing required 'query'.", isError = true)
        val hits = retrieval.search(query)
        if (hits.isEmpty()) {
            return ToolResult("Nenašel jsem k tomu v nápovědě nic konkrétního.")
        }
        return ToolResult(
            hits.joinToString("\n\n") { hit ->
                "${hit.passage.text}\n(zdroj: ${hit.passage.docTitle} — ${hit.passage.source})"
            },
        )
    }
}
