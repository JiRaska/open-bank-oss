// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import com.openbank.govaudit.application.port.out.GovernanceRulesPort
import com.openbank.govaudit.infrastructure.config.GovernanceAuditorConfig
import com.openbank.libs.governance.RulesYamlSections
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
 * Reads `openbank-libs/governance/rules.yaml`'s `review` and `money_path_services` sections
 * live (ADR-0164), resolving a source in priority order:
 *
 * 1. a rules.yaml file mounted at `rules-yaml-path` (blank = not configured), else
 * 2. the raw document at `rules-yaml-url` (blank = not configured), else
 * 3. the config mirror (`money-path-services` etc.) — the pre-live-parsing behaviour.
 *
 * The mirror is a FALLBACK, never silently authoritative: when the live source parses, its values
 * win and a divergence from the mirror is logged at WARN — the mirror list was scaffolded once and
 * never tracks rules.yaml edits (it listed 18 money-path services when the live list had grown to
 * 24), so "mirror and live agree" is the only state in which the mirror is trustworthy. When the
 * live source is unreachable, the fallback is used with a WARN, same fail-open direction as the
 * original stub: a missed governance edit downgrades a finding's severity, it never invents one.
 */
@ApplicationScoped
class GovernanceRulesReadAdapter(private val config: GovernanceAuditorConfig) : GovernanceRulesPort {

    private val log = Logger.getLogger(GovernanceRulesReadAdapter::class.java)

    override suspend fun moneyPathServices(): Set<String> {
        val mirror = config.moneyPathServices().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // RulesYamlSections answers emptySet (not null) for an absent/renamed section, and a 200
        // that is not rules.yaml (an error page, a moved key) parses to exactly that. An empty
        // live set would WIN over the mirror and silently downgrade every money-path PR to the
        // default obligation, so it is treated as "unavailable" -- the fleet always has money-path
        // services, an empty list is never a real governance state.
        val live = liveLines()?.let { RulesYamlSections.moneyPathServices(it) }?.takeIf { it.isNotEmpty() }
        return resolve("money_path_services", live, mirror)
    }

    override suspend fun defaultApprovals(): Int {
        val mirror = config.defaultApprovals()
        return resolve(
            "review.default_approvals",
            liveLines()?.let {
                RulesYamlSections.reviewApprovals(it)?.first
            },
            mirror,
        )
    }

    override suspend fun moneyPathApprovals(): Int {
        val mirror = config.moneyPathApprovals()
        return resolve(
            "review.money_path_approvals",
            liveLines()?.let {
                RulesYamlSections.reviewApprovals(it)?.second
            },
            mirror,
        )
    }

    private fun <T> resolve(section: String, live: T?, mirror: T): T {
        if (live == null) {
            log.warnf("rules.yaml unavailable — using config mirror for %s", section)
            return mirror
        }
        if (live != mirror) {
            log.warnf("rules.yaml %s %s diverges from config mirror %s — live value wins", section, live, mirror)
        }
        return live
    }

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
    // CancellationException still propagates — a cancelled sweep must not degrade into a
    // silent mirror fallback.
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
                log.warnf("Fetching rules.yaml from %s answered %d — using config mirror", url, response.statusCode())
                null
            }
        } catch (e: IOException) {
            log.warnf(e, "Failed to fetch rules.yaml from %s — using config mirror", url)
            null
        } catch (e: InterruptedException) {
            log.warnf(e, "Interrupted fetching rules.yaml from %s — using config mirror", url)
            null
        }
    }

    private companion object {
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
