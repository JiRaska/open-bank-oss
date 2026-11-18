// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.application

import com.openbank.fetchbroker.application.port.out.CharterHostsPort
import com.openbank.fetchbroker.application.port.out.FetchAuditPort
import com.openbank.fetchbroker.application.port.out.FetchMetricsPort
import com.openbank.fetchbroker.application.port.out.RenderRequest
import com.openbank.fetchbroker.application.port.out.RendererPort
import com.openbank.fetchbroker.domain.FetchEnvelope
import com.openbank.fetchbroker.domain.FetchMode
import com.openbank.fetchbroker.domain.FetchOutcome
import com.openbank.fetchbroker.domain.HostAllowlist
import com.openbank.fetchbroker.domain.RenderMode
import com.openbank.fetchbroker.domain.RenderResult
import com.openbank.fetchbroker.domain.UntrustedContent
import com.openbank.fetchbroker.domain.UrlDecision
import java.security.MessageDigest
import java.time.Clock

/** Collaborators of [WebFetchService], grouped so the constructor stays small. */
class FetchCollaborators(
    val charters: CharterHostsPort,
    val plain: RendererPort,
    val js: RendererPort,
    val metrics: FetchMetricsPort,
    val audit: FetchAuditPort,
    val rateLimiter: HostRateLimiter,
)

/**
 * The `web.fetch` use case (ADR-0387). Every terminal branch goes through [finish], so metrics,
 * audit and liveness can never disagree about what happened, and liveness records only a FETCHED.
 */
class WebFetchService(
    private val c: FetchCollaborators,
    private val maxContentChars: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun fetch(agentId: String, url: String, mode: FetchMode): FetchEnvelope {
        val grants = c.charters.grantsFor(agentId)
        val decision = HostAllowlist.decide(url, grants)
        if (decision is UrlDecision.Denied) return denied(agentId, url, decision.reason)
        val allowed = decision as UrlDecision.Allowed
        val host = allowed.grant.host
        if (!c.rateLimiter.tryAcquire(host)) return denied(agentId, url, "per-host rate limit")
        val renderer = if (allowed.grant.render == RenderMode.JS) c.js else c.plain
        val envelope = when (val r = renderer.render(RenderRequest(allowed.uri, mode, grants))) {
            is RenderResult.Rendered -> {
                // Sanitise BEFORE the cap: neutralising can lengthen the text (`>>>` -> `> > >`).
                val clean = UntrustedContent.sanitise(r.text)
                val truncated = clean.length > maxContentChars
                FetchEnvelope(
                    url = url,
                    finalUrl = r.finalUrl,
                    fetchedAt = clock.instant(),
                    sha256 = sha256(r.raw),
                    bytes = r.raw.size,
                    renderer = renderer.name,
                    outcome = FetchOutcome.FETCHED,
                    content = UntrustedContent.wrapSanitised(if (truncated) clean.take(maxContentChars) else clean),
                    truncated = truncated,
                )
            }
            is RenderResult.NotRenderable -> empty(url, renderer.name, FetchOutcome.NOT_RENDERABLE, r.reason)
            is RenderResult.Denied -> empty(url, renderer.name, FetchOutcome.DENIED, r.reason)
        }
        return finish(agentId, envelope, host)
    }

    /** A call refused before any renderer ran (broker allowlist, rate limit, or the shared PDP). */
    suspend fun denied(agentId: String, url: String, reason: String): FetchEnvelope =
        finish(agentId, empty(url, NO_RENDERER, FetchOutcome.DENIED, reason), null)

    private suspend fun finish(agentId: String, envelope: FetchEnvelope, listedHost: String?): FetchEnvelope {
        // Only charter-listed hosts become a tag value, so a caller cannot mint metric series.
        c.metrics.recorded(envelope.outcome, listedHost ?: OFF_LIST_HOST)
        if (envelope.outcome == FetchOutcome.FETCHED) c.metrics.fetchSucceeded()
        c.audit.fetched(agentId, envelope)
        return envelope
    }

    private fun empty(url: String, renderer: String, outcome: FetchOutcome, reason: String) = FetchEnvelope(
        url = url,
        finalUrl = null,
        fetchedAt = clock.instant(),
        sha256 = null,
        bytes = 0,
        renderer = renderer,
        outcome = outcome,
        content = null,
        reason = reason,
    )

    companion object {
        const val NO_RENDERER = "none"
        const val OFF_LIST_HOST = "off_list"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
