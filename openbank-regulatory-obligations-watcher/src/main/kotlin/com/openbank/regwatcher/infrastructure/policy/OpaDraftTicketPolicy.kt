// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.regwatcher.application.port.DraftTicketPolicyPort
import com.openbank.regwatcher.application.port.PolicyVerdict
import com.openbank.regwatcher.infrastructure.config.WatcherConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Asks the pod's OPA sidecar whether this charter may `draft.ticket` (ADR-0031 D9 tier/resource
 * grant: tier `write_proposal`, resource `draft.ticket`), before every issue. The served charter —
 * not this code — is the authority: removing the `draft.ticket` grant from `tools.allow` in
 * agents.yaml withdraws the write. (agents.rego does not read `enabled`; the kill switch is the
 * runtime's.) Fails CLOSED: an unreachable sidecar, a non-2xx, or a body
 * without a boolean `allow` is a deny, recorded as the audit's `policy_decision`.
 */
class OpaDraftTicketPolicy(private val config: WatcherConfig) : DraftTicketPolicyPort {
    private val mapper = ObjectMapper()
    private val http by lazy { HttpClient.newBuilder().connectTimeout(config.policy().timeout()).build() }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun decide(): PolicyVerdict = try {
        withContext(Dispatchers.IO) {
            val input = mapper.writeValueAsString(
                mapOf(
                    "input" to mapOf(
                        "agent" to config.agentId(),
                        "tool" to TOOL,
                        "resource" to TOOL,
                        "tier" to TIER,
                    ),
                ),
            )
            val response = http.send(
                HttpRequest.newBuilder(URI.create("${config.policy().opaUrl().trimEnd('/')}$PATH"))
                    .timeout(config.policy().timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(input))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() !in OK) {
                PolicyVerdict(false, "pdp answered HTTP ${response.statusCode()}")
            } else {
                val result = mapper.readTree(response.body()).path("result")
                val allow = result.path("allow")
                when {
                    !allow.isBoolean -> PolicyVerdict(false, "pdp answer has no boolean allow")
                    allow.booleanValue() -> PolicyVerdict(true, result.path("reason").asText("allowed by charter"))
                    else -> PolicyVerdict(false, result.path("reason").asText("no matching allow rule"))
                }
            }
        }
    } catch (ex: Exception) {
        PolicyVerdict(false, "pdp unreachable: ${ex.javaClass.simpleName}")
    }

    private companion object {
        const val PATH = "/v1/data/openbank/agents/decision"
        const val TOOL = "draft.ticket"
        const val TIER = "write_proposal"
        val OK = 200..299
    }
}
