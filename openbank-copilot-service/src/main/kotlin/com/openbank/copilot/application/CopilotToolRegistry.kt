// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.copilot.application.port.out.CopilotTool
import com.openbank.copilot.application.port.out.ToolResult
import com.openbank.copilot.domain.model.ToolSpec
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Registry of the tools the assistant may offer the model (ADR-0089). Tools are discovered via CDI;
 * the model is only ever shown [specs], and a call is dispatched by name through [call]. The policy
 * gate (deny-by-default) authorises each call independently — registration does not imply permission.
 */
@ApplicationScoped
class CopilotToolRegistry(private val tools: Instance<CopilotTool>) {

    fun specs(): List<ToolSpec> = tools.map { ToolSpec(it.name, it.description, it.inputSchema) }

    fun capabilityOf(name: String): String? = tools.firstOrNull { it.name == name }?.capability

    suspend fun call(name: String, arguments: JsonNode): ToolResult =
        tools.firstOrNull { it.name == name }?.call(arguments)
            ?: ToolResult("Unknown tool '$name'.", isError = true)
}
