// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.ReconciliationSource
import com.openbank.libs.analytics.AggregateKey
import com.openbank.libs.analytics.ServiceReconciliationSummary
import com.openbank.libs.security.ServiceTokenProvider
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * OLTP **source-side** reconciliation reader (ADR-0026): the binding that finally gives
 * [ReconciliationJob] something authoritative to compare the warehouse against, closing the ADR-0023
 * F4/F5 source-side follow-up.
 *
 * It fans out over HTTP to each configured domain service's shared
 * [com.openbank.libs.analytics.ReconciliationSummaryContract] endpoint, merging their per-aggregate
 * `max(version)` and per-type counts into the single union the [ReconciliationSource] port returns.
 * Only versions and counts cross the wire — never payloads — so the drift check never loads the
 * operational databases (ADR-0022/0003), and the sink reads each service *through its own boundary*
 * rather than touching its Postgres directly.
 *
 * It is the `@Alternative @Priority(100)` binding behind the `@Default` [NoOpReconciliationSource],
 * gated at **build time** by `openbank.analytics.reconcile.source.backend=http`, so the default profile
 * still reconciles as a clean no-op (drift = 0) with zero infrastructure and **no new dependency**.
 *
 * Transport is the JDK [HttpClient] behind the overridable [fetch]/[bearerToken] seams, so endpoint
 * parsing, request building and JSON merging are pure and unit-tested without a running server. A
 * service unreachable at run time is logged loudly and excluded from the pass (its keys surface as
 * warehouse-only) rather than failing the whole reconciliation — mirroring the boot-resilient stance
 * of the other ADR-0023 adapters.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "openbank.analytics.reconcile.source.backend", stringValue = "http")
open class HttpReconciliationSource : ReconciliationSource {

    /** `service=baseUrl` pairs, comma-separated (e.g. `account=http://openbank-account-service:8081`).
     *  Optional<String>, not a plain String (CLAUDE.md pitfall): SmallRye's built-in String converter
     *  treats an empty-string-resolved value as "no value" and throws SRCFG00040 at boot. */
    @ConfigProperty(name = "openbank.analytics.reconcile.source.endpoints")
    lateinit var endpointsSpec: Optional<String>

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var tokens: Instance<ServiceTokenProvider>

    private val log = Logger.getLogger(HttpReconciliationSource::class.java)
    private val http: HttpClient by lazy { HttpClient.newHttpClient() }

    // The job calls currentVersions() and rowCountsByType() back-to-back in one pass; the contract
    // returns both in a single document, so a tiny TTL memo coalesces the two reads into one fetch per
    // service per pass instead of transferring the (potentially large) aggregate dump twice. Passes are
    // hours apart (off-peak cron), so the next pass always refetches.
    @Volatile
    private var memo: Pair<Instant, List<ServiceReconciliationSummary>>? = null
    private val memoTtl: Duration = Duration.ofSeconds(30)

    override suspend fun currentVersions(): Map<AggregateKey, Long> = summaries().flatMap { it.aggregates }
        .associate { AggregateKey(it.aggregateType, it.aggregateId) to it.maxVersion }

    override suspend fun rowCountsByType(): Map<String, Long> {
        val merged = HashMap<String, Long>()
        for (summary in summaries()) {
            for ((type, count) in summary.countsByType) merged.merge(type, count, Long::plus)
        }
        return merged
    }

    /** Parses the `service=baseUrl,...` spec, skipping blank/malformed entries (logged). */
    internal fun endpoints(): Map<String, String> = endpointsSpec.orElse("").split(',').mapNotNull { raw ->
        val entry = raw.trim()
        if (entry.isEmpty()) return@mapNotNull null
        val sep = entry.indexOf('=')
        if (sep <= 0 || sep == entry.length - 1) {
            log.warnf("ignoring malformed reconciliation source endpoint spec entry: %s", entry)
            return@mapNotNull null
        }
        entry.substring(0, sep).trim() to entry.substring(sep + 1).trim()
    }.toMap()

    private suspend fun summaries(): List<ServiceReconciliationSummary> {
        memo?.let { (at, value) -> if (Duration.between(at, Instant.now(clock)) < memoTtl) return value }
        val auth = bearerToken()
        val result = endpoints().mapNotNull { (service, baseUrl) ->
            val uri = URI.create("${baseUrl.trimEnd('/')}/api/v1/analytics/reconciliation-summary")
            runCatching { mapper.readValue(fetch(uri, auth), ServiceReconciliationSummary::class.java) }
                .onFailure {
                    log.warnf(it, "reconciliation source unreachable service=%s — excluded from this pass", service)
                }
                .getOrNull()
        }
        memo = Instant.now(clock) to result
        return result
    }

    /** Service-to-service bearer (ADR-0026 §D3), null when no provider is wired so Phase-0 boots clean. */
    protected open fun bearerToken(): String? =
        runCatching { if (tokens.isResolvable) "Bearer ${tokens.get().getToken()}" else null }.getOrNull()

    /** Overridable HTTP seam (tests stub it without a server). Throws on a non-2xx response. */
    protected open suspend fun fetch(uri: URI, authHeader: String?): String = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(uri).header("Accept", "application/json").GET()
        if (authHeader != null) builder.header("Authorization", authHeader)
        val response = http.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) {
            "reconciliation-summary failed: HTTP ${response.statusCode()} ${response.body().take(500)}"
        }
        response.body()
    }
}
