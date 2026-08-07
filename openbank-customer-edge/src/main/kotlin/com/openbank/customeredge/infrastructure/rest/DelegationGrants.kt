// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * The one place the edge asks delegation-service "may this person do this to that" (ADR-0232).
 *
 * Every route that honours a share funnels through here, so the two properties that make delegated
 * access safe are decided once instead of per caller:
 *
 *  - **Fail closed.** A delegation-service that is down, slow or unparseable DENIES. Guessing
 *    "probably allowed" discloses someone else's document or balance; guessing the other way makes
 *    a shared thing briefly unavailable. Only one of those is a breach.
 *  - **No cache, no local projection.** A revoked or suspended grant stops working on the next
 *    read, not at the end of a TTL. Revocation that takes effect "soon" is not revocation, and
 *    this is the read path to someone else's money and papers.
 */
@ApplicationScoped
class DelegationGrants(private val upstream: UpstreamClient) {

    @ConfigProperty(
        name = "openbank.edge.delegation-service-url",
        defaultValue = "http://delegation-service.delegation.svc:8126",
    )
    lateinit var delegationServiceUrl: String

    private val json = ObjectMapper()

    /** Whether an ACTIVE grant gives [granteePartyId] [capability] over this exact resource. */
    fun has(granteePartyId: String, resourceType: String, resourceId: String, capability: String): Boolean {
        val body = """
            {"granteePartyId":"$granteePartyId","resourceType":"$resourceType",
            "resourceId":"$resourceId","capability":"$capability"}
        """.trimIndent().replace("\n", "")
        val resp = runCatching {
            upstream.post("$delegationServiceUrl/api/v1/delegations/check", granteePartyId, body)
        }.getOrNull() ?: return false
        if (resp.status != HTTP_OK) return false
        return runCatching {
            json.readTree(resp.entity?.toString() ?: "").path("granted").asBoolean(false)
        }.getOrDefault(false)
    }

    /**
     * Resource ids of [resourceType] shared WITH this party and carrying [capability].
     *
     * OFFERED grants are absent on purpose: an offer nobody accepted is not access, and listing it
     * would show the grantee something they have not agreed to hold.
     */
    fun activeResourceIds(granteePartyId: String, resourceType: String, capability: String): List<String> {
        val resp = runCatching {
            upstream.get("$delegationServiceUrl/api/v1/delegations/grantee/$granteePartyId", granteePartyId)
        }.getOrNull() ?: return emptyList()
        if (resp.status != HTTP_OK) return emptyList()
        val grants = runCatching { json.readTree(resp.entity?.toString() ?: "") }.getOrNull()
            ?.takeIf { it.isArray } ?: return emptyList()
        return grants.asSequence()
            .filter { it.path("status").asText() == "ACTIVE" }
            .filter { it.path("resourceType").asText() == resourceType }
            .filter { g -> g.path("capabilities").any { it.asText() == capability } }
            .mapNotNull { it.path("resourceId").asText(null) }
            .distinct()
            .toList()
    }

    /** Read-capabilities that make a shared ACCOUNT worth listing. Execution capabilities are absent. */
    fun accountReadCapabilities(): Set<String> = ACCOUNT_READ_CAPABILITIES

    private companion object {
        const val HTTP_OK = 200
        val ACCOUNT_READ_CAPABILITIES = setOf("ACCOUNT_READ_BALANCES", "ACCOUNT_READ_TRANSACTIONS")
    }
}

/** Convenience for the JSON projections that follow a grant lookup. */
internal fun JsonNode.textOrNull(field: String): String? = this.get(field)?.asText()
