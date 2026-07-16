// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Keycloak client for the WebAuthn RP's two identity operations (ADR-0066 F2, variant B1):
 * creating a Keycloak user for a brand-new party, and minting that user a real session token via
 * token-exchange. Uses the dedicated `openbank-edge-webauthn` service-account client in the
 * `openbank-customers` realm — separate from [com.openbank.customeredge.infrastructure.rest.KeycloakAdminClient]'s
 * `customer-edge-admin` (which only ever sets the `party_id` attribute post-hoc). This client
 * carries `manage-users` (for [ensureUser]) AND the `token-exchange` fine-grained-authz
 * permission on `openbank-app` (for [impersonate]) — a broader grant than `customer-edge-admin`
 * needs, so keeping them separate keeps each client's blast radius minimal.
 *
 * `manage-users`/`view-users`/`impersonation` role assignment and the token-exchange
 * fine-grained-authz permission binding are live Keycloak realm state, not captured by the
 * git-tracked realm-import template (same gap as `customer-edge-admin`'s roles) — verified live
 * against `kc.open-bank.tech` 2026-07-14 (both a `client_credentials` token mint and a
 * `requested_subject`+`audience=openbank-app` token-exchange succeeded end to end).
 */
/**
 * The identity behind a customer's own access token, as told by Keycloak introspection.
 * [keycloakUserId] is the token's `sub` — the EXISTING Keycloak user, which is why a caller
 * holding one must never be routed through [WebAuthnKeycloakClient.ensureUser] (that would mint a
 * second, synthetic-email user for a party that already has one).
 */
data class CustomerTokenIdentity(val partyId: String, val keycloakUserId: String)

@ApplicationScoped
class WebAuthnKeycloakClient {

    @ConfigProperty(name = "openbank.edge.keycloak-admin-url", defaultValue = "http://keycloak.iam.svc:8080")
    var adminUrl: String = "http://keycloak.iam.svc:8080"

    @ConfigProperty(name = "openbank.edge.keycloak-admin-realm", defaultValue = "openbank-customers")
    var realm: String = "openbank-customers"

    @ConfigProperty(name = "openbank.webauthn.kc-client-id", defaultValue = "openbank-edge-webauthn")
    var clientId: String = "openbank-edge-webauthn"

    @ConfigProperty(name = "openbank.webauthn.kc-client-secret", defaultValue = "")
    var clientSecret: String = ""

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
        .build()

    private val json = ObjectMapper()

    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenExpiresAt: Long = 0L

    @Synchronized
    private fun serviceToken(): String {
        val now = System.currentTimeMillis() / MILLIS_PER_SECOND
        val cached = cachedToken
        if (cached != null && tokenExpiresAt - now > TOKEN_REFRESH_SKEW_SECONDS) return cached
        val (token, expiresIn) = grant("grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret")
        cachedToken = token
        tokenExpiresAt = now + expiresIn
        return token
    }

    private fun grant(formBody: String): Pair<String, Long> {
        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("$adminUrl/realms/$realm/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(resp.statusCode() == HTTP_OK) { "Keycloak token endpoint returned ${resp.statusCode()}: ${resp.body()}" }
        val tree = json.readTree(resp.body())
        val token = tree.get("access_token")?.asText() ?: error("token response missing access_token")
        return token to (tree.get("expires_in")?.asLong() ?: DEFAULT_EXPIRES_IN_SECONDS)
    }

    /**
     * Find-or-create a Keycloak user for a newly onboarded party and return its Keycloak user id
     * (UUID). The realm has `registrationEmailAsUsername=true`, so [email] doubles as username;
     * `party_id` is set as a user attribute so the existing `party-id` protocol mapper on
     * `openbank-app` picks it up on any FUTURE token this user obtains via the normal hosted
     * flow too (not just the token [impersonate] mints here).
     */
    fun ensureUser(email: String, partyId: String, displayName: String): String {
        val token = serviceToken()
        val encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8)
        val searchResp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("$adminUrl/admin/realms/$realm/users?email=$encodedEmail&exact=true"))
                .header("Authorization", "Bearer $token")
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(searchResp.statusCode() == HTTP_OK) { "Keycloak GET /users returned ${searchResp.statusCode()}" }
        val existing = json.readTree(searchResp.body()).firstOrNull()
        if (existing != null) return existing.get("id").asText()

        val nameParts = displayName.trim().split(Regex("\\s+"), limit = 2)
        val createBody = json.writeValueAsString(
            mapOf(
                "username" to email,
                "email" to email,
                "emailVerified" to true, // this account is bound to an already-KYC'd party, not a fresh signup
                "enabled" to true,
                "firstName" to nameParts.getOrElse(0) { "" },
                "lastName" to nameParts.getOrElse(1) { "" },
                "attributes" to mapOf("party_id" to listOf(partyId)),
            ),
        )
        val createResp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("$adminUrl/admin/realms/$realm/users"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(createResp.statusCode() == HTTP_CREATED) {
            "Keycloak POST /users returned ${createResp.statusCode()}: ${createResp.body()}"
        }
        val location = createResp.headers().firstValue("Location").orElse(null)
            ?: error("Keycloak user create response had no Location header")
        return location.substringAfterLast("/")
    }

    /**
     * Mint a real session token for [keycloakUserId] via RFC 8693 token-exchange, scoped to the
     * `openbank-app` audience so the result carries the same claims (`sub`, `realm_access.roles`,
     * `party_id` if already set) a normal hosted login would produce. Not routed through
     * [serviceToken]'s cache — unlike [ensureUser] this is a `client_id`+`client_secret`-authenticated
     * grant in its own right, and its response (unlike a plain `client_credentials` one) carries a
     * `refresh_token` the caller needs too.
     */
    fun impersonate(keycloakUserId: String): Pair<String, String?> {
        val grantType = URLEncoder.encode(TOKEN_EXCHANGE_GRANT_TYPE, StandardCharsets.UTF_8)
        val formBody = "grant_type=$grantType&client_id=$clientId&client_secret=$clientSecret" +
            "&requested_subject=$keycloakUserId&audience=openbank-app"
        val resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("$adminUrl/realms/$realm/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(resp.statusCode() == HTTP_OK) {
            "Keycloak token-exchange returned ${resp.statusCode()}: ${resp.body()}"
        }
        val tree = json.readTree(resp.body())
        val accessToken = tree.get("access_token")?.asText() ?: error("token-exchange response missing access_token")
        val refreshToken = tree.get("refresh_token")?.asText()
        Log.debugf("impersonate: minted token for kcUserId=%s", keycloakUserId)
        return accessToken to refreshToken
    }

    /**
     * Verify a customer's own Keycloak access token via RFC 7662 introspection and return the
     * identity it carries, or null if the token is absent/expired/forged/from another realm.
     *
     * Why introspection and not the injected [org.eclipse.microprofile.jwt.JsonWebToken]:
     * [WebAuthnResource] is deliberately un-annotated so that the registration routes can accept an
     * [EnrollmentTicketService] ticket, which is NOT a JWT. Touching the injected token there would
     * make Quarkus authenticate the request and reject a ticket-bearing call outright (the very
     * failure that class's KDoc warns about). Introspection asks Keycloak to do the signature and
     * expiry checks out-of-band, so the ticket path stays untouched — and rolling one's own JWT
     * verification here would be strictly worse than delegating to the issuer.
     *
     * The endpoint is realm-scoped, so a token minted by any other realm is `active: false`.
     */
    fun introspectCustomerToken(accessToken: String): CustomerTokenIdentity? {
        val formBody = "client_id=$clientId&client_secret=$clientSecret" +
            "&token=${URLEncoder.encode(accessToken, StandardCharsets.UTF_8)}"
        val resp = runCatching {
            http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$adminUrl/realms/$realm/protocol/openid-connect/token/introspect"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrElse { e ->
            // Fail closed: an unreachable Keycloak must not enrol an unverified credential.
            Log.warnf(e, "introspectCustomerToken: introspection call failed")
            return null
        }
        if (resp.statusCode() != HTTP_OK) {
            Log.warnf("introspectCustomerToken: introspection returned %d", resp.statusCode())
            return null
        }
        val tree = runCatching { json.readTree(resp.body()) }.getOrNull() ?: return null
        if (tree.get("active")?.asBoolean() != true) return null
        val subject = tree.get("sub")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        // Same precedence as every other customer route (CustomerEdgeResource.resolvePartyIdClaim):
        // the explicit party_id claim wins, sub is the ADR-0069 `party.id == sub` fallback.
        val partyId = tree.get("party_id")?.asText()?.takeIf { it.isNotBlank() } ?: subject
        return CustomerTokenIdentity(partyId = partyId, keycloakUserId = subject)
    }

    companion object {
        private const val TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
        private const val HTTP_TIMEOUT_SECONDS = 5L
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val MILLIS_PER_SECOND = 1000L

        // Refresh the cached service token this many seconds before its nominal expiry so a
        // concurrent caller never races a token that's about to be rejected.
        private const val TOKEN_REFRESH_SKEW_SECONDS = 60L

        // Keycloak's token response always includes expires_in in practice; this is only a
        // conservative fallback if a future response ever omits it.
        private const val DEFAULT_EXPIRES_IN_SECONDS = 60L
    }
}
