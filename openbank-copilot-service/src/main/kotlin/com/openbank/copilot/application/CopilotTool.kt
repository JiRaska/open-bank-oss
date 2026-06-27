// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.copilot.application

import com.fasterxml.jackson.databind.JsonNode

/** Result of running a tool. [isError] tells the loop the round failed (auth/connectivity/not-found). */
data class ToolResult(val text: String, val isError: Boolean = false)

/**
 * A capability the assistant may invoke on the customer's behalf (ADR-0089). Phase-1 tools are
 * READ-only over the customer's OWN data: the call runs as the customer (propagated bearer), so the
 * downstream service re-enforces ownership and the assistant can never reach another customer's data.
 * Figures returned here are for the model to NARRATE, never to invent (ADR-0089 D4). [capability] is
 * the coarse action the policy gate authorises (deny-by-default, ADR-0034).
 */
interface CopilotTool {
    val name: String
    val description: String
    val capability: String
    val inputSchema: Map<String, Any>

    suspend fun call(arguments: JsonNode): ToolResult
}
