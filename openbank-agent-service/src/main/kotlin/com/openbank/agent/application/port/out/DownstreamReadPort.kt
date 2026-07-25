// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import com.fasterxml.jackson.databind.JsonNode

/**
 * Outbound port: the read-only view of the running bank that the MCP read tools expose
 * (ADR-0002 hexagonal, ADR-0031). One port, not fifteen, because from the application's side there
 * is exactly one capability here — "resolve a registered read tool's arguments into downstream
 * data" — and the fan-out to account-service / ledger-service / Prometheus / … is precisely the
 * transport detail the port exists to hide.
 *
 * Implemented by [com.openbank.agent.infrastructure.client.RestDownstreamReadAdapter], which owns
 * the MicroProfile `@RestClient` interfaces, the bearer propagation and the per-tool argument
 * marshalling. [com.openbank.agent.application.McpToolRegistry] keeps what is governance: the tool
 * catalog, the charter-capability mapping, the HITL proposal tools and the AI-attributed audit.
 *
 * Every method here is READ-only and blocking (callers run on a `@Blocking` worker thread).
 * An implementation MUST NOT mutate downstream state — the only non-read MCP tools are the
 * proposal tools, and those never reach this port.
 */
interface DownstreamReadPort {

    /**
     * True when [toolName] is a downstream read this port serves. The registry asks first so that
     * an unregistered tool name stays an application-level "unknown tool", not a transport error.
     */
    fun handles(toolName: String): Boolean

    /**
     * Perform the read for [toolName] and return the downstream document verbatim.
     *
     * @throws IllegalArgumentException when a required argument is missing or blank, or when
     *   [toolName] is not one this port [handles] — the registry maps it to `invalid_params`.
     */
    fun read(toolName: String, arguments: JsonNode): JsonNode
}
