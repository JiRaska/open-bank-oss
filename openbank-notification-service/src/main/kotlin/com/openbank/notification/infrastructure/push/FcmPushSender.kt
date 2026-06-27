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
 * Firebase Cloud Messaging adapter (FCM HTTP v1).
 *
 * OFF by default (`openbank.notification.push.fcm.enabled=false`) — when disabled, [send]
 * returns a *skipped* (successful no-op) result, mirroring the EMAIL stub and the off-by-default
 * Slack oversight webhook (ADR-0059). The service-account credentials are injected at runtime
 * from Vault via ExternalSecret, never committed to git.
 *
 * Auth flow: build a short-lived RS256 JWT assertion signed by the service-account private key,
 * exchange it for an OAuth2 access token (cached until 60s before expiry), then POST the message
 * to `/v1/projects/{projectId}/messages:send`. All I/O is non-blocking ([HttpClient.sendAsync]
 * bridged to Mutiny) so it composes inside the consumer's reactive chain.
 */
@ApplicationScoped
class FcmPushSender {

    @ConfigProperty(name = "openbank.notification.push.fcm.enabled", defaultValue = "false")
    var enabled: Boolean = false

    // Raw service-account JSON, or its Base64 encoding. Supplied via env/Vault. Optional<String>
    // (not String): SmallRye treats "" as absent and would fail to convert it to a bare String —
    // Optional maps an unset/empty value to empty(), keeping the adapter inert.
    @ConfigProperty(name = "openbank.notification.push.fcm.service-account-json")
    var serviceAccountJson: Optional<String> = Optional.empty()

    // Optional override; otherwise project_id from the service-account JSON is used.
    @ConfigProperty(name = "openbank.notification.push.fcm.project-id")
    var projectIdOverride: Optional<String> = Optional.empty()

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(FcmPushSender::class.java)

    private data class ServiceAccount(
        val clientEmail: String,
        val privateKey: PrivateKey,
        val tokenUri: String,
        val projectId: String,
    )

    private val account: ServiceAccount? by lazy { parseAccount() }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    }

    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenExpiresAtEpochSec: Long = 0L

    fun send(message: PushMessage): Uni<PushResult> {
        if (!enabled) return Uni.createFrom().item(PushResult.skipped("FCM disabled"))
        val acct = account
            ?: return Uni.createFrom().item(PushResult.failed("CONFIG", "FCM service account not configured"))
        if (acct.projectId.isBlank()) {
            return Uni.createFrom().item(PushResult.failed("CONFIG", "FCM projectId not configured"))
        }
        return accessToken(acct)
            .chain { token -> sendMessage(acct, token, message) }
            .onFailure().recoverWithItem { e -> PushResult.failed("FCM_ERROR", e.message) }
    }

    private fun accessToken(acct: ServiceAccount): Uni<String> {
        val now = System.currentTimeMillis() / 1000L
        val cached = cachedToken
        if (cached != null && tokenExpiresAtEpochSec - now > TOKEN_REFRESH_BUFFER_SEC) {
            return Uni.createFrom().item(cached)
        }
        val assertion = buildAssertion(acct, now)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(acct.tokenUri))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(REQUEST_TIMEOUT)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$assertion",
                ),
            )
            .build()
        return Uni.createFrom().completionStage(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .map { resp ->
                check(resp.statusCode() == 200) { "FCM token endpoint returned ${resp.statusCode()}" }
                val node = objectMapper.readTree(resp.body())
                val token = node.path("access_token").asText()
                val expiresIn = node.path("expires_in").asLong(3600L)
                cachedToken = token
                tokenExpiresAtEpochSec = now + expiresIn
                token
            }
    }

    private fun buildAssertion(acct: ServiceAccount, nowEpochSec: Long): String {
        val header = PushCrypto.b64Url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = PushCrypto.b64Url(
            (
                """{"iss":"${acct.clientEmail}","scope":"https://www.googleapis.com/auth/firebase.messaging",""" +
                    """"aud":"${acct.tokenUri}","iat":$nowEpochSec,"exp":${nowEpochSec + 3600}}"""
                ).toByteArray(),
        )
        val signingInput = "$header.$claims"
        return "$signingInput.${PushCrypto.signRs256(signingInput, acct.privateKey)}"
    }

    private fun sendMessage(acct: ServiceAccount, token: String, message: PushMessage): Uni<PushResult> {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "message" to mapOf(
                    "token" to message.token,
                    "notification" to mapOf("title" to message.title, "body" to message.body),
                    "data" to message.data,
                ),
            ),
        )
        val url = "https://fcm.googleapis.com/v1/projects/${acct.projectId}/messages:send"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        return Uni.createFrom().completionStage(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .map { resp -> mapResponse(resp.statusCode(), resp.body()) }
    }

    /** Visible for testing. Maps an FCM v1 HTTP response to a [PushResult]. */
    internal fun mapResponse(statusCode: Int, body: String?): PushResult {
        if (statusCode in 200..299) {
            val name = runCatching { objectMapper.readTree(body).path("name").asText(null) }.getOrNull()
            return PushResult.ok(name)
        }
        // FCM v1 error envelope: {"error":{"status":"NOT_FOUND"|"INVALID_ARGUMENT"|...,"message":...}}
        val status = runCatching { objectMapper.readTree(body).path("error").path("status").asText(null) }.getOrNull()
        val invalidToken = statusCode == 404 ||
            status == "NOT_FOUND" ||
            status == "UNREGISTERED" ||
            (statusCode == 400 && status == "INVALID_ARGUMENT")
        return PushResult.failed(status ?: "HTTP_$statusCode", body?.take(200), invalidToken = invalidToken)
    }

    private fun parseAccount(): ServiceAccount? {
        val raw = serviceAccountJson.orElse("").trim()
        if (raw.isBlank()) return null
        return try {
            val json = if (raw.startsWith("{")) raw else String(Base64.getDecoder().decode(raw))
            val node = objectMapper.readTree(json)
            ServiceAccount(
                clientEmail = node.path("client_email").asText(),
                privateKey = PushCrypto.parsePkcs8PrivateKey(node.path("private_key").asText(), "RSA"),
                tokenUri = node.path("token_uri").asText("https://oauth2.googleapis.com/token"),
                projectId = projectIdOverride.orElse("").takeIf { it.isNotBlank() }
                    ?: node.path("project_id").asText(""),
            )
        } catch (e: Exception) {
            log.errorf(e, "Failed to parse FCM service account JSON; FCM sends will fail")
            null
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val TOKEN_REFRESH_BUFFER_SEC = 60L
    }
}
