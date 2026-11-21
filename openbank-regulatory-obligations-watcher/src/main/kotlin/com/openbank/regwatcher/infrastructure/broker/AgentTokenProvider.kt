// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.broker

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.regwatcher.infrastructure.config.WatcherConfig
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Supplies the bearer token the fetch broker requires. Null = no credential (fail closed). */
fun interface AgentTokenSource {
    suspend fun token(): String?
}

/**
 * client_credentials against the watcher's OWN Keycloak client (`openbank-regulatory-obligations-
 * watcher`). That client's mappers set `sub = agent:regulatory-obligations-watcher` and add
 * `openbank-fetch-broker` to `aud` (realm-template.json), which is exactly what the broker's
 * AgentCaller requires; the client holds no realm role, so the token opens nothing else. The
 * secret is never logged. An absent secret answers null, and the broker call is then not made.
 */
@ApplicationScoped
class AgentTokenProvider(private val config: WatcherConfig) : AgentTokenSource {
    private val log = Logger.getLogger(AgentTokenProvider::class.java)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_SECONDS)).build()

    @Volatile
    private var cached: Pair<String, Instant>? = null

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun token(): String? {
        cached?.let { (t, exp) -> if (Instant.now().isBefore(exp)) return t }
        val secret = config.identity().clientSecret().orElse("")
        if (secret.isBlank()) {
            log.warn("regulatory-watcher client secret not seeded — no broker token, every source reads as UNVERIFIED")
            return null
        }
        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to config.identity().clientId(),
            "client_secret" to secret,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        return try {
            withContext(Dispatchers.IO) {
                val response = http.send(
                    HttpRequest.newBuilder(URI.create(config.identity().tokenUrl()))
                        .timeout(Duration.ofSeconds(REQUEST_SECONDS))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                if (response.statusCode() !in OK) {
                    log.warnf("token endpoint answered HTTP %d — no broker token", response.statusCode())
                    return@withContext null
                }
                val body = mapper.readTree(response.body())
                val token = body.path("access_token").asText("").ifBlank { null } ?: return@withContext null
                val ttl = body.path("expires_in").asLong(DEFAULT_TTL_SECONDS)
                cached = token to Instant.now().plusSeconds((ttl - SKEW_SECONDS).coerceAtLeast(0))
                token
            }
        } catch (ex: Exception) {
            log.warnf("token endpoint call failed (%s) — no broker token", ex.javaClass.simpleName)
            null
        }
    }

    private companion object {
        const val CONNECT_SECONDS = 5L
        const val REQUEST_SECONDS = 10L
        const val DEFAULT_TTL_SECONDS = 60L
        const val SKEW_SECONDS = 30L
        val OK = 200..299
    }
}
