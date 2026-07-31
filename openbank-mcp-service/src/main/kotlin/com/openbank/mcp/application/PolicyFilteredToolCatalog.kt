// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import com.openbank.mcp.application.protocol.ToolDefinition
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Policy-filtered `tools/list` (ADR-0225): discovery is capability-shaped, so the caller only ever
 * sees the tools the shared ADR-0034 PDP would let it call. A model cannot be talked into
 * attempting a tool it never learns about, and the operations vocabulary stops being a
 * reconnaissance map handed to every authenticated agent.
 *
 * The filter evaluates the SAME `(principal, capability)` pair the `tools/call` gate evaluates, so
 * a tool that would be denied at call time is absent at discovery time — never a second rule set.
 * Three invariants mirror the call path exactly:
 *
 *  - **deny-by-default**: a tool with no capability mapping is never listed (the call path refuses
 *    it with "no capability mapping");
 *  - **fail closed**: a PDP transport error excludes that tool; a full outage yields an empty list;
 *  - **membership only**: the schema of a listed tool is identical for every caller (ADR-0225 D2) —
 *    per-caller schema mutation is deliberately not implemented.
 *
 * The ADR asks for a "single batched PDP query". The [PolicyDecisionPoint] port has no batch shape
 * and adding one (a rego rule returning an allowed-subset) is a bundle change owned by the ADR-0223
 * programme; until then the evaluations run CONCURRENTLY over the existing port plus a short-TTL
 * per-principal cache, which is what bounds the round-trip cost in practice. Recorded here so the
 * deviation is a decision, not an omission.
 *
 * The cache also caches failure outcomes (an empty list during a PDP outage) for the TTL — the
 * alternative, hammering a struggling PDP with retries from every listing client, is worse; the
 * TTL bounds how long a recovered PDP goes unnoticed.
 */
@ApplicationScoped
class PolicyFilteredToolCatalog @Inject constructor(
    private val registry: McpToolRegistry,
    private val pdp: PolicyDecisionPoint,
    @ConfigProperty(name = "mcp.tools-list.cache-ttl-ms", defaultValue = DEFAULT_CACHE_TTL_MS)
    private val cacheTtlMs: Long,
) {

    /** Outcome of one filtered `tools/list` — the audit event and meter are derived from it. */
    data class FilterResult(
        /** Tools the caller may see: exactly those the PDP allows, schemas untouched (D2). */
        val tools: List<ToolDefinition>,
        /** Registered tool count before filtering — the audit event's denominator. */
        val total: Int,
        /** Capability evaluations lost to PDP transport errors (each excluded its tool, fail-closed). */
        val pdpErrors: Int,
        /** True when served from the short-TTL cache — no PDP round-trip happened for this call. */
        val fromCache: Boolean,
    )

    private data class Cached(val at: Instant, val tools: List<ToolDefinition>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val log = Logger.getLogger(PolicyFilteredToolCatalog::class.java)

    /**
     * The tools [principal] may see, policy-filtered. [cacheKey] must identify the principal (and
     * any scope that changes the decision, e.g. the presented consent) — it is the cache key.
     */
    suspend fun visibleTools(principal: Principal, cacheKey: String): FilterResult {
        cache[cacheKey]
            ?.takeIf { Duration.between(it.at, Instant.now()).toMillis() < cacheTtlMs }
            ?.let { return FilterResult(it.tools, registry.tools.size, pdpErrors = 0, fromCache = true) }

        val pdpErrors = AtomicInteger(0)
        val allowed: List<ToolDefinition> = coroutineScope {
            registry.tools.map { tool ->
                async {
                    val capability = registry.capabilities[tool.name] ?: return@async null
                    val permitted = try {
                        pdp.allow(
                            AuthzQuery(
                                principal = principal,
                                action = capability,
                                resource = null,
                                attributes = mapOf("tool" to tool.name),
                            ),
                        ).allow
                    } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
                        log.warnf("PDP error filtering tool %s from discovery: %s — excluding", tool.name, ex.message)
                        pdpErrors.incrementAndGet()
                        false
                    }
                    if (permitted) tool else null
                }
            }.map { it.await() }.filterNotNull()
        }
        cache[cacheKey] = Cached(Instant.now(), allowed)
        return FilterResult(allowed, registry.tools.size, pdpErrors.get(), fromCache = false)
    }

    private companion object {
        const val DEFAULT_CACHE_TTL_MS = "30000"
    }
}
