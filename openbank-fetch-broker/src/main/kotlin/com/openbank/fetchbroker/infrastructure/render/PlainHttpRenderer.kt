// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.render

import com.openbank.fetchbroker.application.port.out.RenderRequest
import com.openbank.fetchbroker.application.port.out.RendererPort
import com.openbank.fetchbroker.domain.FetchMode
import com.openbank.fetchbroker.domain.HostAllowlist
import com.openbank.fetchbroker.domain.HtmlTextExtractor
import com.openbank.fetchbroker.domain.RenderResult
import com.openbank.fetchbroker.domain.UrlDecision
import com.openbank.libs.security.EgressDeniedException
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResponse
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset

/**
 * Default renderer (ADR-0387 D3): GET via [SafeHttpClient][com.openbank.libs.security.SafeHttpClient],
 * redirects followed only while every hop stays on the charter allowlist, robots.txt checked on
 * every hop, text extracted in-cluster (HTML, PDF, plain text).
 */
class PlainHttpRenderer(
    private val transport: HttpTransport,
    private val robots: RobotsGate,
    private val userAgent: String,
) : RendererPort {
    override val name = NAME

    @Suppress("ReturnCount")
    override fun render(request: RenderRequest): RenderResult {
        var current: URI = request.uri
        repeat(MAX_HOPS) {
            if (!robots.isAllowed(current)) return RenderResult.Denied("robots.txt disallows ${current.rawPath}")
            val resp = try {
                transport.send(
                    EgressRequest("GET", current.toString(), mapOf("User-Agent" to userAgent, "Accept" to ACCEPT)),
                    request.grants.keys,
                )
            } catch (ex: EgressDeniedException) {
                return RenderResult.Denied("egress denied: ${ex.decision.reason}")
            } catch (ex: IOException) {
                return RenderResult.NotRenderable("transport failure: ${ex.javaClass.simpleName}")
            }
            if (resp.status in REDIRECTS) {
                val location = resp.header("location") ?: return RenderResult.NotRenderable("redirect without location")
                val next = runCatching { current.resolve(location).toString() }.getOrNull()
                    ?: return RenderResult.Denied("malformed redirect")
                when (val d = HostAllowlist.decide(next, request.grants)) {
                    is UrlDecision.Denied -> return RenderResult.Denied("redirect refused: ${d.reason}")
                    is UrlDecision.Allowed -> current = d.uri
                }
            } else {
                return extract(current, resp, request.mode)
            }
        }
        return RenderResult.NotRenderable("too many redirects")
    }

    private fun extract(uri: URI, resp: EgressResponse, mode: FetchMode): RenderResult {
        if (resp.status != HTTP_OK) return RenderResult.NotRenderable("http status ${resp.status}")
        val contentType = resp.header("content-type").orEmpty().lowercase()
        val text = when {
            "html" in contentType -> HtmlTextExtractor.extract(decode(resp, contentType), mode)
            "application/pdf" in contentType -> pdfText(resp.body)
                ?: return RenderResult.NotRenderable("unreadable pdf")
            contentType.startsWith("text/plain") -> decode(resp, contentType)
            else -> return RenderResult.NotRenderable("unsupported content type")
        }
        if (text.isBlank()) return RenderResult.NotRenderable("no extractable text")
        return RenderResult.Rendered(uri.toString(), resp.body, text.replace("\u0000", ""))
    }

    private fun decode(resp: EgressResponse, contentType: String): String {
        val charset = contentType.substringAfter("charset=", "").substringBefore(';').trim('"', ' ')
        val cs = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
        return String(resp.body, cs)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun pdfText(bytes: ByteArray): String? = try {
        Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val NAME = "plain-http"
        private const val MAX_HOPS = 5
        private const val HTTP_OK = 200
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val ACCEPT = "text/html,application/xhtml+xml,application/pdf,text/plain;q=0.8"
    }
}
