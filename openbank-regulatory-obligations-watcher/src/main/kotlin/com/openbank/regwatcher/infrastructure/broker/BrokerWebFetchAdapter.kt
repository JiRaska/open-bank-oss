// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.broker

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.regwatcher.application.port.WebFetchPort
import com.openbank.regwatcher.domain.SourceFetch
import com.openbank.regwatcher.domain.SourceOutcome
import com.openbank.regwatcher.infrastructure.config.WatcherConfig
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * `web.fetch` over the broker's MCP endpoint (JSON-RPC 2.0, `tools/call`, ADR-0387 D1/D4).
 *
 * The broker is the ONLY way this agent reads the web: its own NetworkPolicy reaches the broker on
 * 8172 and nothing on the internet. Every failure becomes an outcome — no credential, a transport
 * error, a 401, a JSON-RPC error or an unparseable envelope all answer a non-FETCHED outcome with a
 * reason, so a source this run could not read is reported UNVERIFIED and never as unchanged.
 * The content is kept exactly as the broker wrapped it (untrusted-content delimiters included).
 */
@ApplicationScoped
class BrokerWebFetchAdapter(private val config: WatcherConfig, private val tokens: AgentTokenSource) : WebFetchPort {
    private val log = Logger.getLogger(BrokerWebFetchAdapter::class.java)
    private val mapper = ObjectMapper()
    private val ids = AtomicLong()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_SECONDS)).build()

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun fetch(url: String): SourceFetch {
        val token = tokens.token() ?: return failed(url, SourceOutcome.DENIED, "no agent credential")
        val body = mapper.writeValueAsString(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to ids.incrementAndGet(),
                "method" to "tools/call",
                "params" to mapOf("name" to "web.fetch", "arguments" to mapOf("url" to url, "mode" to "text")),
            ),
        )
        return try {
            withContext(Dispatchers.IO) {
                val response = http.send(
                    HttpRequest.newBuilder(URI.create(config.broker().mcpUrl()))
                        .timeout(config.broker().timeout())
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $token")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                if (response.statusCode() !in OK) {
                    return@withContext failed(
                        url,
                        SourceOutcome.DENIED,
                        "broker answered HTTP ${response.statusCode()}",
                    )
                }
                parse(url, response.body())
            }
        } catch (ex: Exception) {
            log.warnf("broker call for %s failed: %s", url, ex.javaClass.simpleName)
            failed(url, SourceOutcome.NOT_RENDERABLE, "broker unavailable: ${ex.javaClass.simpleName}")
        }
    }

    @Suppress("ReturnCount")
    internal fun parse(url: String, raw: String): SourceFetch {
        val rpc = mapper.readTree(raw)
        if (rpc.has(
                "error",
            )
        ) {
            return failed(
                url,
                SourceOutcome.DENIED,
                "broker error: ${rpc.path("error").path("message").asText().take(MAX_REASON)}",
            )
        }
        val text = rpc.path("result").path("content").path(0).path("text").asText("")
        val env = runCatching { mapper.readTree(text) }.getOrNull()
            ?: return failed(url, SourceOutcome.DENIED, "broker refused: ${text.take(MAX_REASON)}")
        val outcome = when (env.path("outcome").asText()) {
            "FETCHED" -> SourceOutcome.FETCHED
            "NOT_RENDERABLE" -> SourceOutcome.NOT_RENDERABLE
            else -> SourceOutcome.DENIED
        }
        val sha = env.path("sha256").takeIf { it.isTextual }?.asText()
        val content = env.path("content").takeIf { it.isTextual }?.asText()
        if (outcome == SourceOutcome.FETCHED && (sha.isNullOrBlank() || content.isNullOrBlank())) {
            return failed(url, SourceOutcome.NOT_RENDERABLE, "FETCHED envelope without sha256/content")
        }
        return SourceFetch(
            url = url,
            outcome = outcome,
            sha256 = if (outcome == SourceOutcome.FETCHED) sha else null,
            content = if (outcome == SourceOutcome.FETCHED) content else null,
            reason = env.path("reason").takeIf { it.isTextual }?.asText()?.take(MAX_REASON),
            fetchedAt = runCatching { Instant.parse(env.path("fetched_at").asText()) }.getOrElse { Instant.now() },
        )
    }

    private fun failed(url: String, outcome: SourceOutcome, reason: String) =
        SourceFetch(url, outcome, null, null, reason, Instant.now())

    private companion object {
        const val CONNECT_SECONDS = 5L
        const val MAX_REASON = 200
        val OK = 200..299
    }
}
