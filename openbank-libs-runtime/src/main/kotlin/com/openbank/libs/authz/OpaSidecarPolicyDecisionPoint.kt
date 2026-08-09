// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Production [PolicyDecisionPoint] — posts to the per-service OPA sidecar
 * (ADR-0018 + ADR-0034). Plain `java.net.http.HttpClient` rather than
 * Quarkus REST client so this class compiles inside `openbank-libs` without
 * dragging in a Quarkus runtime extension; the libs JAR stays
 * runtime-agnostic (`compileOnly` everywhere — see build.gradle.kts).
 *
 * Configuration (typically resolved via `@ConfigProperty` at the service
 * level and passed to the constructor):
 *   - `opa.url`     default `http://localhost:8181`
 *   - `opa.path`    default `/v1/data/openbank/rest/allow`
 *   - `opa.timeout` default `PT0.5S`
 *
 * The 500 ms timeout matches the existing MCP gate budget (ADR-0031 D2);
 * exceeding it is treated as a fail-closed deny rather than masking a
 * sidecar outage as an allow. Network errors raise [PolicyDecisionException]
 * so callers (the [AuthorizeInterceptor]) can map to HTTP 503 and not
 * pretend the user was forbidden.
 */
class OpaSidecarPolicyDecisionPoint(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val queryPath: String = DEFAULT_QUERY_PATH,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val mapper: ObjectMapper = ObjectMapper(),
    httpClient: HttpClient? = null,
) : PolicyDecisionPoint {
    private val http: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    override suspend fun allow(query: AuthzQuery): AuthzDecision = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$queryPath"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("input" to query.toInput()))))
            .build()

        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { throw PolicyDecisionException("OPA call failed: ${it.message}", it) }

        if (response.statusCode() !in 200..299) {
            throw PolicyDecisionException("OPA returned HTTP ${response.statusCode()}: ${response.body()}")
        }

        parseResponse(response.body())
    }

    private fun AuthzQuery.toInput(): Map<String, Any?> = buildMap {
        put(
            "principal",
            mapOf(
                "id" to principal.id,
                "type" to principal.type,
                "roles" to principal.roles,
                "attributes" to principal.attributes,
            ),
        )
        put("action", action)
        // `let` rather than a smart cast: AuthzQuery.resource is a public API property of
        // openbank-libs-DOMAIN, and Kotlin refuses to smart-cast across a module boundary
        // (the declaring module could change it). Only surfaced once this adapter moved out
        // of libs-domain in #3670 — a same-module compile cannot see it.
        resource?.let { put("resource", mapOf("type" to it.type, "id" to it.id)) }
        put("attributes", attributes)
    }

    private fun parseResponse(body: String): AuthzDecision {
        val root = mapper.readTree(body)
        val result = root.path("result")
        if (result.isMissingNode || result.isNull) {
            // OPA returns {} for "no matching rule" — that is a deny (default in
            // rest.rego). Mirror agents.rego behavior so audit trail is symmetric.
            return AuthzDecision(allow = false, reason = "no matching policy rule")
        }
        // Two shapes are accepted: a bare boolean (simple policy) or an object
        // {allow, reason, policy_version, attributes} (full ADR-0034 contract).
        if (result.isBoolean) {
            return AuthzDecision(allow = result.asBoolean(), reason = null, policyVersion = null)
        }
        return AuthzDecision(
            allow = result.path("allow").asBoolean(false),
            reason = result.path("reason").asText(null),
            policyVersion = result.path("policy_version").asText(null),
            attributes = mapper.convertValue(
                result.path("attributes"),
                Map::class.java,
            ).orEmpty().mapKeys { it.key.toString() } as Map<String, Any?>,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "http://localhost:8181"
        const val DEFAULT_QUERY_PATH: String = "/v1/data/openbank/rest/allow"
        val DEFAULT_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}

/** Sidecar is unreachable / returned garbage — distinct from a policy-driven deny. */
class PolicyDecisionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
