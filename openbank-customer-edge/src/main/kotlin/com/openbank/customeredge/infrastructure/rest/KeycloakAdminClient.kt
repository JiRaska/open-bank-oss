// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin Keycloak Admin REST client used exclusively to set the `party_id`
 * user attribute after a MATCH_EXISTING identity re-link (ADR-0072 §5 / issue #1270 PR3).
 *
 * Uses a dedicated `customer-edge-admin` service-account client in the
 * `openbank-customers` realm (created in the realm template). This client has
 * `manage-users` on that realm only — it cannot manage users in the operator realm.
 *
 * All calls are best-effort: a failure never blocks the response already sent to the
 * mobile client. Setting the attribute causes the *next* KC token to carry the correct
 * `party_id` claim (via the existing user-attribute protocol mapper in the realm).
 */
@ApplicationScoped
class KeycloakAdminClient {

    @ConfigProperty(name = "openbank.edge.keycloak-admin-url", defaultValue = "http://keycloak.iam.svc:8080")
    var adminUrl: String = "http://keycloak.iam.svc:8080"

    @ConfigProperty(name = "openbank.edge.keycloak-admin-realm", defaultValue = "openbank-customers")
    var adminRealm: String = "openbank-customers"

    @ConfigProperty(name = "openbank.edge.keycloak-admin-client-id", defaultValue = "customer-edge-admin")
    var adminClientId: String = "customer-edge-admin"

    // Secret supplied at runtime via OPENBANK_EDGE_KEYCLOAK_ADMIN_CLIENT_SECRET env var,
    // mapped by Quarkus to the dotted form. Not declared as ${...} expansion here —
    // same pattern as UpstreamClient.clientSecret to avoid silent "" default.
    @ConfigProperty(name = "openbank.edge.keycloak-admin-client-secret", defaultValue = "")
    var adminClientSecret: String = ""

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = ObjectMapper()

    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenExpiresAt: Long = 0L

    @Synchronized
    private fun adminToken(): String {
        val now = System.currentTimeMillis() / 1000L
        val cached = cachedToken
        if (cached != null && tokenExpiresAt - now > 60L) return cached
        return fetchAdminToken().also { (t, exp) ->
            cachedToken = t
            tokenExpiresAt = now + exp
        }.first
    }

    private fun fetchAdminToken(): Pair<String, Long> {
        val tokenUrl = "$adminUrl/realms/$adminRealm/protocol/openid-connect/token"
        val requestBody = "grant_type=client_credentials&client_id=$adminClientId&client_secret=$adminClientSecret"
        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(resp.statusCode() == 200) {
            "Keycloak admin token endpoint returned ${resp.statusCode()}"
        }
        val tree = json.readTree(resp.body())
        val token = tree.get("access_token")?.asText()
            ?: error("Keycloak admin token response missing access_token")
        val expiresIn = tree.get("expires_in")?.asLong() ?: 300L
        return token to expiresIn
    }

    /**
     * Set the `party_id` attribute on a Keycloak user. The attribute is picked up by
     * the `party_id` protocol mapper in the customers realm so it appears in the *next*
     * token the user obtains (a token refresh is required after this call).
     *
     * Best-effort: returns false (and logs) on failure; callers must never block on it.
     */
    fun setPartyIdAttribute(keycloakSub: String, partyId: String): Boolean = runCatching {
        if (adminClientSecret.isBlank()) {
            Log.warnf(
                "keycloak-admin-client-secret not configured — skipping party_id attribute set for sub=%s",
                keycloakSub,
            )
            return@runCatching false
        }
        val url = "$adminUrl/admin/realms/$adminRealm/users/$keycloakSub"
        // Merge only the party_id attribute — other attributes are left intact.
        val requestBody = """{"attributes":{"party_id":["$partyId"]}}"""
        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer ${adminToken()}")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val ok = resp.statusCode() in 200..299
        if (!ok) {
            Log.warnf(
                "setPartyIdAttribute: Keycloak PUT /users/%s returned %d",
                keycloakSub,
                resp.statusCode(),
            )
        }
        ok
    }.getOrElse { e ->
        Log.warnf("setPartyIdAttribute failed for sub=%s: %s", keycloakSub, e.message)
        false
    }
}
