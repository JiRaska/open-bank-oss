// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.push

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.domain.model.PushResult
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PrivateKey
import java.time.Duration
import java.util.Base64
import java.util.Optional

/**
 * Apple Push Notification service adapter (token-based / HTTP/2).
 *
 * OFF by default (`openbank.notification.push.apns.enabled=false`) — when disabled, [send]
 * returns a *skipped* (successful no-op) result, mirroring the EMAIL stub. The signing key
 * (.p8), key id, team id and bundle id are injected at runtime from Vault, never in git.
 *
 * Auth: a provider JWT signed ES256 with the .p8 key (kid = key id, iss = team id). Apple
 * allows the token to be reused for up to an hour, so it is cached and refreshed well inside
 * that window. APNs mandates HTTP/2 — the JDK [HttpClient] negotiates it by default, so unlike
 * the cleartext upstream client we do NOT pin HTTP/1.1 here.
 */
@ApplicationScoped
class ApnsPushSender {

    @ConfigProperty(name = "openbank.notification.push.apns.enabled", defaultValue = "false")
    var enabled: Boolean = false

    // Optional<String> (not String): SmallRye treats "" as absent and cannot convert it to a bare
    // String — Optional maps unset/empty to empty(), keeping the adapter inert until configured.
    @ConfigProperty(name = "openbank.notification.push.apns.key-id")
    var keyId: Optional<String> = Optional.empty()

    @ConfigProperty(name = "openbank.notification.push.apns.team-id")
    var teamId: Optional<String> = Optional.empty()

    // Default must match the app's PRODUCT_BUNDLE_IDENTIFIER (openbank-app iosApp/project.yml),
    // since it becomes the apns-topic header — a mismatch is rejected BadTopic. Overridable per
    // env; the default exists so an unconfigured deploy fails the right way, not silently wrong.
    @ConfigProperty(name = "openbank.notification.push.apns.bundle-id", defaultValue = "tech.openbank.app")
    var bundleId: String = "tech.openbank.app"

    // .p8 PEM contents (PKCS#8 EC private key), raw or Base64-encoded. Via env/Vault.
    @ConfigProperty(name = "openbank.notification.push.apns.private-key")
    var privateKeyPem: Optional<String> = Optional.empty()

    // true → APNs sandbox host (debug builds); false → production host.
    @ConfigProperty(name = "openbank.notification.push.apns.sandbox", defaultValue = "false")
    var sandbox: Boolean = false

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(ApnsPushSender::class.java)

    private val signingKey: PrivateKey? by lazy { parseKey() }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    }

    @Volatile private var cachedJwt: String? = null

    @Volatile private var jwtIssuedAtEpochSec: Long = 0L

    fun send(message: PushMessage): Uni<PushResult> {
        if (!enabled) return Uni.createFrom().item(PushResult.skipped("APNs disabled"))
        val key = signingKey
            ?: return Uni.createFrom().item(PushResult.failed("CONFIG", "APNs signing key not configured"))
        if (keyId.orElse("").isBlank() || teamId.orElse("").isBlank()) {
            return Uni.createFrom().item(PushResult.failed("CONFIG", "APNs keyId/teamId not configured"))
        }
        val host = if (sandbox) "api.sandbox.push.apple.com" else "api.push.apple.com"
        val payload = objectMapper.writeValueAsString(
            buildMap<String, Any> {
                put("aps", mapOf("alert" to mapOf("title" to message.title, "body" to message.body)))
                putAll(message.data) // custom keys live at the top level, alongside "aps"
            },
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://$host/3/device/${message.token}"))
            .header("authorization", "bearer ${providerToken(key)}")
            .header("apns-topic", bundleId)
            .header("apns-push-type", "alert")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        return Uni.createFrom().completionStage(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .map { resp ->
                mapResponse(resp.statusCode(), resp.headers().firstValue("apns-id").orElse(null), resp.body())
            }
            .onFailure().recoverWithItem { e -> PushResult.failed("APNS_ERROR", e.message) }
    }

    private fun providerToken(key: PrivateKey): String {
        val now = System.currentTimeMillis() / 1000L
        val cached = cachedJwt
        if (cached != null && now - jwtIssuedAtEpochSec < JWT_TTL_SEC) return cached
        val header = PushCrypto.b64Url("""{"alg":"ES256","kid":"${keyId.orElse("")}"}""".toByteArray())
        val claims = PushCrypto.b64Url("""{"iss":"${teamId.orElse("")}","iat":$now}""".toByteArray())
        val signingInput = "$header.$claims"
        val jwt = "$signingInput.${PushCrypto.signEs256(signingInput, key)}"
        cachedJwt = jwt
        jwtIssuedAtEpochSec = now
        return jwt
    }

    /** Visible for testing. Maps an APNs HTTP/2 response to a [PushResult]. */
    internal fun mapResponse(statusCode: Int, apnsId: String?, body: String?): PushResult {
        if (statusCode in 200..299) return PushResult.ok(apnsId)
        // APNs error body: {"reason":"Unregistered"|"BadDeviceToken"|"DeviceTokenNotForTopic"|...}
        val reason = runCatching { objectMapper.readTree(body).path("reason").asText(null) }.getOrNull()
        val invalidToken = statusCode == 410 ||
            reason == "Unregistered" ||
            reason == "BadDeviceToken" ||
            reason == "DeviceTokenNotForTopic"
        return PushResult.failed(reason ?: "HTTP_$statusCode", body?.take(200), invalidToken = invalidToken)
    }

    private fun parseKey(): PrivateKey? {
        val raw = privateKeyPem.orElse("").trim()
        if (raw.isBlank()) return null
        return try {
            val pem = if (raw.contains("BEGIN")) raw else String(Base64.getDecoder().decode(raw))
            PushCrypto.parsePkcs8PrivateKey(pem, "EC")
        } catch (e: Exception) {
            log.errorf(e, "Failed to parse APNs .p8 signing key; APNs sends will fail")
            null
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val JWT_TTL_SEC = 2400L // refresh well inside Apple's 1h reuse window
    }
}
