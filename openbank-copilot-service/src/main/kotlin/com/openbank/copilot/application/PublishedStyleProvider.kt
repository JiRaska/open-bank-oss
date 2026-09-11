// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import com.openbank.copilot.application.port.out.PublishedStylePort
import com.openbank.copilot.infrastructure.client.PublishedStyleDto
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** What a caller actually gets back — either a real published style, or the git baseline. */
sealed interface ResolvedStyle {
    val version: String

    data class Published(val style: PublishedStyleDto) : ResolvedStyle {
        override val version: String get() = style.styleVersion.toString()
    }

    data class Baseline(val text: String) : ResolvedStyle {
        override val version: String = "baseline"
    }
}

/**
 * ADR-0285 D5's consumer-side cache-and-fall-back shape: "hold the published version in memory,
 * refresh on the event or on a short TTL, and fall back to the git-registered baseline... The bot
 * never goes silent because the studio is down; it merely reverts to the last engineer-reviewed
 * voice, and says so in the audit envelope (`style_version: baseline`)."
 *
 * Refresh-on-event is not implemented: `communication.persona.published.v1` has no producer yet
 * (`UnwiredCommunicationEventPublisher`, an accepted D5 residual risk on the producer side), so
 * TTL poll is the only refresh path that actually fires today — consistent with that.
 *
 * NOT wired into `CopilotChatService.systemPrompt()` yet — see `RegisteredPromptTemplates`'s KDoc
 * for why. This class is real, tested infrastructure ready for that cutover, not itself the cutover.
 */
@ApplicationScoped
class PublishedStyleProvider(private val port: PublishedStylePort, private val clock: Clock) {

    private data class CacheEntry(val style: ResolvedStyle.Published, val fetchedAt: Instant)

    private val cache = AtomicReference<CacheEntry?>(null)

    suspend fun currentStyle(personaKey: String): ResolvedStyle {
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
            cached?.style ?: ResolvedStyle.Baseline(RegisteredPromptTemplates.customerCopilotStyleV1)
        }
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofMinutes(5)
        val LOG: Logger = Logger.getLogger(PublishedStyleProvider::class.java)
    }
}
