// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.libs.governance.RulesYamlSections
import com.openbank.liveness.application.port.out.GovernanceReadPort
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

/**
 * Reads the ADR-0160 advisory allowlists live from `openbank-libs/governance/rules.yaml`
 * (ADR-0163 mechanism 1+2 follow-up), resolving a source in priority order:
 *
 * 1. a rules.yaml file mounted at `rules-yaml-path` (blank = not configured), else
 * 2. the raw document at `rules-yaml-url` (blank = not configured), else
 * 3. an EMPTY allowlist — the original stub's fail-open contract, unchanged: an un-allowlisted
 *    item is still surfaced, just without the "already tracked" distinction. A missing or
 *    unreachable rules.yaml must never SUPPRESS a finding; emptyset only ever makes the sentinel
 *    louder.
 */
@ApplicationScoped
class GovernanceReadAdapter(private val config: LivenessSentinelConfig) : GovernanceReadPort {

    private val log = Logger.getLogger(GovernanceReadAdapter::class.java)

    override suspend fun eventConsumerAllowlist(): Set<String> =
        liveLines()?.let { RulesYamlSections.allowlistValues(it, EVENT_CONSUMER_RULE, TOPIC_KEY) }
            ?: emptySet()

    override suspend fun lineageAllowlist(): Set<String> =
        liveLines()?.let { RulesYamlSections.allowlistValues(it, LINEAGE_RULE, EDGE_KEY) }
            ?: emptySet()

    private suspend fun liveLines(): List<String>? {
        val path = config.rulesYamlPath().orElse("")
        if (path.isNotBlank()) {
            val file = Path.of(path)
            if (file.exists()) {
                return readLines(file)
            }
            log.warnf("rules-yaml-path %s configured but the file does not exist — trying rules-yaml-url", path)
        }
        val url = config.rulesYamlUrl().orElse("")
        if (url.isBlank()) return null
        return fetch(url)
    }

    private fun readLines(file: Path): List<String>? = runCatching { Files.readAllLines(file) }.getOrElse { e ->
        log.warnf(e, "Failed to read rules.yaml at %s", file)
        null
    }

    // IOException/InterruptedException are caught specifically (not Exception) so a coroutine
    // CancellationException still propagates — a cancelled check must not degrade into a
    // silent empty allowlist (which would re-flag every already-tracked exemption).
    private suspend fun fetch(url: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build()
            val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == HTTP_OK) {
                response.body().lines()
            } else {
                log.warnf(
                    "Fetching rules.yaml from %s answered %d — allowlists resolve empty",
                    url,
                    response.statusCode(),
                )
                null
            }
        } catch (e: IOException) {
            log.warnf(e, "Failed to fetch rules.yaml from %s — allowlists resolve empty", url)
            null
        } catch (e: InterruptedException) {
            log.warnf(e, "Interrupted fetching rules.yaml from %s — allowlists resolve empty", url)
            null
        }
    }

    private companion object {
        const val EVENT_CONSUMER_RULE = "event_consumer_liveness"
        const val LINEAGE_RULE = "lineage_code_audit"
        const val TOPIC_KEY = "topic"
        const val EDGE_KEY = "edge"
        const val HTTP_OK: Int = HttpURLConnection.HTTP_OK
        const val CONNECT_TIMEOUT_SECONDS: Long = 5
        const val FETCH_TIMEOUT_SECONDS: Long = 10
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)
        val FETCH_TIMEOUT: Duration = Duration.ofSeconds(FETCH_TIMEOUT_SECONDS)
        val HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
