// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.render

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fetchbroker.application.port.out.RenderRequest
import com.openbank.fetchbroker.application.port.out.RendererPort
import com.openbank.fetchbroker.domain.FetchMode
import com.openbank.fetchbroker.domain.HostAllowlist
import com.openbank.fetchbroker.domain.HtmlTextExtractor
import com.openbank.fetchbroker.domain.RenderResult
import com.openbank.fetchbroker.domain.UrlDecision
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResponse
import java.io.IOException

/**
 * JS renderer for hosts a charter marks `render: js` (ADR-0387 D3): Cloudflare Browser Run Quick
 * Actions over REST with `browser=kitesurf` — never CDP. [baseUrl] is the account's
 * browser-rendering endpoint, [apiToken] the `browser-run-api-token` secret; both come from config
 * and neither is in the repo. Unconfigured or failing ⇒ NOT_RENDERABLE, never an empty success.
 */
class KitesurfRenderer(
    private val baseUrl: String?,
    private val apiToken: String?,
    private val transport: HttpTransport,
    private val robots: RobotsGate,
    private val mapper: ObjectMapper,
) : RendererPort {
    override val name = NAME

    @Suppress("ReturnCount")
    override fun render(request: RenderRequest): RenderResult {
        val base = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val token = apiToken?.trim()?.takeIf { it.isNotEmpty() }
        if (base == null || token == null) return RenderResult.NotRenderable("js renderer not configured")
        val providerHost = HostAllowlist.hostOf(base) ?: return RenderResult.NotRenderable("js renderer misconfigured")
        if (!robots.isAllowed(request.uri)) return RenderResult.Denied("robots.txt disallows ${request.uri.rawPath}")
        val action = if (request.mode == FetchMode.MARKDOWN) "markdown" else "content"
        val resp = try {
            transport.send(
                EgressRequest(
                    "POST",
                    "$base/$action?browser=kitesurf",
                    mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                    mapper.writeValueAsBytes(mapOf("url" to request.uri.toString())),
                ),
                setOf(providerHost),
            )
        } catch (ex: IOException) {
            return RenderResult.NotRenderable("js renderer unreachable: ${ex.javaClass.simpleName}")
        }
        return parse(resp, request)
    }

    @Suppress("ReturnCount")
    private fun parse(resp: EgressResponse, request: RenderRequest): RenderResult {
        if (resp.status != HTTP_OK) return RenderResult.NotRenderable("js renderer status ${resp.status}")
        val node = runCatching { mapper.readTree(resp.body) }.getOrNull()
            ?: return RenderResult.NotRenderable("js renderer returned no content")
        val result = node.path("result").takeIf { node.path("success").asBoolean(false) && it.isTextual }?.asText()
        if (result.isNullOrBlank()) return RenderResult.NotRenderable("js renderer returned no content")
        val confirmed = when (val f = confirmedFinalUrl(node, request)) {
            is RenderResult.NotRenderable -> return f
            else -> (f as RenderResult.Rendered).finalUrl
        }
        val text = if (request.mode == FetchMode.MARKDOWN) result else HtmlTextExtractor.extract(result, FetchMode.TEXT)
        if (text.isBlank()) return RenderResult.NotRenderable("js renderer returned no content")
        return RenderResult.Rendered(confirmed, result.toByteArray(), text.replace("\u0000", ""))
    }

    /**
     * The provider's browser follows redirects on its side, out of our sight. A final URL is
     * reported only when the provider states one AND it is on the caller's allowlist — never
     * assumed equal to the requested URL. Returns a Rendered carrying only that URL, or the refusal.
     */
    private fun confirmedFinalUrl(node: JsonNode, request: RenderRequest): RenderResult {
        val stated = FINAL_URL_FIELDS.firstNotNullOfOrNull { field ->
            (
                node.path(field).takeIf {
                    it.isTextual
                } ?: node.path("meta").path(field).takeIf { it.isTextual }
                )?.asText()
        } ?: return RenderResult.NotRenderable("final url unconfirmed")
        return when (val d = HostAllowlist.decide(stated, request.grants)) {
            is UrlDecision.Allowed -> RenderResult.Rendered(d.uri.toString(), ByteArray(0), "")
            is UrlDecision.Denied -> RenderResult.NotRenderable("redirected off allowlist")
        }
    }

    companion object {
        const val NAME = "kitesurf"
        private const val HTTP_OK = 200
        private val FINAL_URL_FIELDS = listOf("finalUrl", "final_url")
    }
}
