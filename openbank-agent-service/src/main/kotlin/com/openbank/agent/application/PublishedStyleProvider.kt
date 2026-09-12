// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.application

import com.openbank.agent.application.port.out.PublishedStylePort
import com.openbank.agent.infrastructure.client.PublishedStyleDto
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** What a caller actually gets back for a successful (cached-or-fresh) published-style read. */
sealed interface ResolvedStyle {
    val version: String

    data class Published(val style: PublishedStyleDto) : ResolvedStyle {
        override val version: String get() = style.styleVersion.toString()
    }
}

/**
 * ADR-0285 D5's consumer-side cache-and-fall-back shape for `ui-assistant`, mirroring
 * `openbank-copilot-service`'s `PublishedStyleProvider` — hold the published version in memory,
 * refresh on a short TTL, serve the last known-good value on a transient failure.
 *
 * **Unlike copilot-service's provider, this one has NO git-registered-baseline fallback.**
 * `openbank-libs/governance/prompts/registry.yaml`'s `ui-assistant` entry has not been split into
 * a `core.v1`/`style.v1` pair the way `customer-copilot` has (it registers only
 * `system.v1`/`system.v2`/`system.v3`/`catalog-review.v1` — the whole, unsplit prompts actually
 * served by `AgentChatService`/`CatalogReviewService`). Inventing a style baseline here would mean
 * fabricating prompt content that has never been registered or reviewed under ADR-0148 — that is a
 * decision for whoever authors the `ui-assistant` core/style split, not something to guess in this
 * infrastructure-only change. So on total failure (no warm cache AND the fetch itself failed),
 * [currentStyle] returns `null` rather than a synthesized baseline: callers must treat a `null`
 * result as "no style layer available for this persona today", not as an empty-but-valid style.
 *
 * Refresh-on-event is not implemented: `communication.persona.published.v1` has no producer yet
 * (same accepted D5 residual risk noted in copilot-service's provider), so TTL poll is the only
 * refresh path that actually fires today.
 *
 * NOT wired into `AgentChatService.systemPrompt()` or `CatalogReviewService` — this class is real,
 * tested infrastructure, not itself a runtime-behaviour change. See the threat model's matching
 * open item for what a future cutover would still need.
 */
@ApplicationScoped
class PublishedStyleProvider(private val port: PublishedStylePort, private val clock: Clock) {

    private data class CacheEntry(val style: ResolvedStyle.Published, val fetchedAt: Instant)

    private val cache = AtomicReference<CacheEntry?>(null)

    suspend fun currentStyle(personaKey: String): ResolvedStyle? {
        val cached = cache.get()
        if (cached != null && Duration.between(cached.fetchedAt, Instant.now(clock)) < CACHE_TTL) {
            return cached.style
        }
        return runCatching {
            val dto = port.fetch(personaKey)
            ResolvedStyle.Published(dto).also { cache.set(CacheEntry(it, Instant.now(clock))) }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            LOG.warnf(e, "published style fetch failed for persona=%s — falling back", personaKey)
            // Re-read the cache rather than reuse the `cached` snapshot captured before the fetch:
            // a concurrent caller can have raced this one past TTL expiry, fetched successfully,
            // and already written a fresh entry — falling back to the stale pre-fetch snapshot
            // would discard a value that is, at this exact moment, valid.
            cache.get()?.style
        }
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofMinutes(5)
        val LOG: Logger = Logger.getLogger(PublishedStyleProvider::class.java)
    }
}
