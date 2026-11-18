// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.render

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fetchbroker.application.port.out.RenderRequest
import com.openbank.fetchbroker.application.port.out.RendererPort
import com.openbank.fetchbroker.domain.FetchMode
import com.openbank.fetchbroker.domain.HostAllowlist
import com.openbank.fetchbroker.domain.HtmlTextExtractor
import com.openbank.fetchbroker.domain.RenderResult
import com.openbank.libs.security.EgressRequest
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
        if (resp.status != HTTP_OK) return RenderResult.NotRenderable("js renderer status ${resp.status}")
        val node = runCatching { mapper.readTree(resp.body) }.getOrNull()
        val result = node?.path("result")?.takeIf { node.path("success").asBoolean(false) && it.isTextual }?.asText()
        if (result.isNullOrBlank()) return RenderResult.NotRenderable("js renderer returned no content")
        val text = if (request.mode == FetchMode.MARKDOWN) result else HtmlTextExtractor.extract(result, FetchMode.TEXT)
        if (text.isBlank()) return RenderResult.NotRenderable("js renderer returned no content")
        return RenderResult.Rendered(request.uri.toString(), result.toByteArray(), text.replace("\u0000", ""))
    }

    companion object {
        const val NAME = "kitesurf"
        private const val HTTP_OK = 200
    }
}
