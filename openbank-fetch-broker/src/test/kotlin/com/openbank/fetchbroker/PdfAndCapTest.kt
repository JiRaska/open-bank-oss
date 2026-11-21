// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker

import com.openbank.fetchbroker.application.FetchCollaborators
import com.openbank.fetchbroker.application.HostRateLimiter
import com.openbank.fetchbroker.application.WebFetchService
import com.openbank.fetchbroker.application.port.out.FetchAuditPort
import com.openbank.fetchbroker.application.port.out.FetchMetricsPort
import com.openbank.fetchbroker.application.port.out.RenderRequest
import com.openbank.fetchbroker.application.port.out.RendererPort
import com.openbank.fetchbroker.domain.FetchEnvelope
import com.openbank.fetchbroker.domain.FetchMode
import com.openbank.fetchbroker.domain.FetchOutcome
import com.openbank.fetchbroker.domain.HostGrant
import com.openbank.fetchbroker.domain.RenderResult
import com.openbank.fetchbroker.infrastructure.render.HttpTransport
import com.openbank.fetchbroker.infrastructure.render.PlainHttpRenderer
import com.openbank.fetchbroker.infrastructure.render.RobotsGate
import com.openbank.libs.security.EgressResponse
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class PdfAndCapTest {
    private val grants = mapOf("example.org" to HostGrant("example.org"))

    @Test
    fun `pdf extraction is disabled by default and answers NOT_RENDERABLE`() {
        val transport = HttpTransport { req, _ ->
            if (req.url.endsWith("/robots.txt")) {
                EgressResponse(404, emptyMap(), ByteArray(0))
            } else {
                EgressResponse(200, mapOf("content-type" to listOf("application/pdf")), "%PDF-1.7".toByteArray())
            }
        }
        // The production default: no extractor argument.
        val r = PlainHttpRenderer(transport, RobotsGate(transport, "UA/1"), "UA/1")
            .render(RenderRequest(URI("https://example.org/a.pdf"), FetchMode.TEXT, grants))
        assertThat(r).isEqualTo(RenderResult.NotRenderable(PlainHttpRenderer.PDF_DISABLED))
    }

    @Test
    fun `the content cap is applied after sanitising, so neutralising cannot overflow it`(): Unit = runBlocking {
        val cap = 9
        val renderer = object : RendererPort {
            override val name = "fake"

            override fun render(request: RenderRequest) =
                RenderResult.Rendered(request.uri.toString(), ByteArray(1), ">".repeat(cap))
        }
        val noop = object : FetchMetricsPort, FetchAuditPort {
            override fun recorded(outcome: FetchOutcome, host: String) = Unit

            override fun fetchSucceeded() = Unit

            override suspend fun fetched(agentId: String, envelope: FetchEnvelope) = Unit
        }
        val service = WebFetchService(
            FetchCollaborators({ grants }, renderer, renderer, noop, noop, HostRateLimiter(Duration.ZERO)),
            maxContentChars = cap,
        )
        val env = service.fetch("agent:x", "https://example.org/", FetchMode.TEXT)
        val body = env.content!!.lines().let { it.subList(2, it.size - 1) }.joinToString("\n")
        assertThat(body.length).isLessThanOrEqualTo(cap)
        assertThat(body).doesNotContain(">>>")
        assertThat(env.truncated).isTrue()
    }
}
