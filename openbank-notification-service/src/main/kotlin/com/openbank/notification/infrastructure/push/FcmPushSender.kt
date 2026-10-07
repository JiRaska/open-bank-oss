// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.push

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResolver
import com.openbank.libs.security.SafeHttpClient
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.domain.model.PushResult
import com.openbank.notification.infrastructure.egress.NotificationEgress
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
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
 * to `/v1/projects/{projectId}/messages:send`. Egress goes through [SafeHttpClient] (ADR-0320 P1):
 * the token URI comes from the service-account JSON, so it must name an allow-listed host. The
 * blocking call runs on the worker pool, so it composes inside the consumer's reactive chain.
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

    @ConfigProperty(
        name = NotificationEgress.ALLOWED_HOSTS_PROPERTY,
        defaultValue = NotificationEgress.DEFAULT_ALLOWED_HOSTS,
    )
    lateinit var allowedHosts: List<String>

    /** Visible for testing: lets a unit test pin a stub host to loopback. */
    internal var resolver: EgressResolver = EgressResolver.SYSTEM

    /** Test seam for the fixed FCM endpoint; production always uses Google's URL. */
    internal var testSendUrl: String? = null

    private val http: SafeHttpClient by lazy {
        NotificationEgress.client(allowedHosts, resolver, CONNECT_TIMEOUT, REQUEST_TIMEOUT, MAX_RESPONSE_BYTES)
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
        // A send exception may follow FCM acceptance. Keep the PENDING notification in doubt;
        // the consumer dead-letters the request instead of asserting a definitive failure.
        return accessToken(acct)
            .chain { token -> sendMessage(acct, token, message) }
    }

    private fun accessToken(acct: ServiceAccount): Uni<String> {
        val now = System.currentTimeMillis() / 1000L
        val cached = cachedToken
        if (cached != null && tokenExpiresAtEpochSec - now > TOKEN_REFRESH_BUFFER_SEC) {
            return Uni.createFrom().item(cached)
        }
        val assertion = buildAssertion(acct, now)
        val request = EgressRequest(
            method = "POST",
            url = acct.tokenUri,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$assertion"
                .toByteArray(Charsets.UTF_8),
        )
        return NotificationEgress.send({ http }, request)
            .map { resp ->
                check(resp.status == 200) { "FCM token endpoint returned ${resp.status}" }
                val node = objectMapper.readTree(resp.bodyAsString())
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
        val url = testSendUrl ?: "https://fcm.googleapis.com/v1/projects/${acct.projectId}/messages:send"
        val request = EgressRequest(
            method = "POST",
            url = url,
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
            body = payload.toByteArray(Charsets.UTF_8),
        )
        return NotificationEgress.send({ http }, request)
            .map { resp -> mapResponse(resp.status, resp.bodyAsString()) }
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
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
