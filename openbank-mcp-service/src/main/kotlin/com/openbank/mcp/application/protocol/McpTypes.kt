// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp.application.protocol

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

@JsonInclude(JsonInclude.Include.NON_NULL)
data class McpRequest(val jsonrpc: String = "2.0", val id: JsonNode?, val method: String, val params: JsonNode?)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: JsonNode?,
    val result: Any? = null,
    val error: McpError? = null,
)

data class McpError(val code: Int, val message: String, val data: Any? = null)

object McpErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

data class ServerInfo(val name: String, val version: String)

data class ServerCapabilities(
    val tools: ToolsCapability = ToolsCapability(),
    val resources: ResourcesCapability = ResourcesCapability(),
)

data class ToolsCapability(val listChanged: Boolean = false)
data class ResourcesCapability(val subscribe: Boolean = false, val listChanged: Boolean = false)

/**
 * `instructions` is the MCP handshake's optional server-guidance field, and it is where this
 * server declares its untrusted-data marker convention (see
 * [com.openbank.mcp.application.McpUntrustedData.PREAMBLE], issue #2412). A marker whose
 * meaning is never stated is decoration — this server does not own the calling model's system
 * prompt, so the handshake is the only channel the protocol gives it to say what the markers
 * around every tool result mean. Non-null always; the field is declared nullable only so the
 * type can express a client-facing optional per the spec.
 */
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo,
    val instructions: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    // Downstream service + product domain this tool reaches (#744). Populated on the tools/list
    // response so the admin-ui coverage grid can group verb-first tools (get_account, …) by
    // service without a fragile name-prefix heuristic. Null for clients that don't set them.
    val service: String? = null,
    val domain: String? = null,
)

data class ToolsListResult(val tools: List<ToolDefinition>)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolContent(val type: String = "text", val text: String)

data class ToolCallResult(val content: List<ToolContent>, val isError: Boolean = false)
