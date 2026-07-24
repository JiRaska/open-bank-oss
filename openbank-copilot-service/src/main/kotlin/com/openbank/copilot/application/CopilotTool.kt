// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import com.fasterxml.jackson.databind.JsonNode

/**
 * Result of running a tool. [isError] tells the loop the round failed (auth/connectivity/not-found).
 * [themeSpecJson] is set only by the theme-designer tool (ADR-0190): the normalized ThemeSpec the
 * chat loop lifts onto the reply / stream sentinel so the app can apply it — it never rides the
 * model-facing text.
 */
data class ToolResult(val text: String, val isError: Boolean = false, val themeSpecJson: String? = null)

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
