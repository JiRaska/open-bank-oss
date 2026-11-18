// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.rest

import com.openbank.copilot.domain.AI_DISCLOSURE
import io.quarkus.vertx.http.runtime.filters.Filters
import io.vertx.core.http.HttpMethod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Adds the machine-readable AI-generated marker (EU AI Act Art. 50(1)) to every response of
 * `POST /api/v1/copilot/chat/stream`, before the first event is written.
 *
 * Why a Vert.x HTTP filter and not a JAX-RS filter or `RestMulti`: measured on this service, headers
 * set through `RestMulti` on an SSE response never reach the client, and a JAX-RS response filter ran
 * after the stream head was committed. The HTTP-layer filter runs first, so the marker is present
 * whatever the outcome (reply, refusal, disabled, error fallback, empty stream).
 */
@ApplicationScoped
class CopilotStreamAiMarkerFilter {

    fun register(@Observes filters: Filters) {
        filters.register(
            { ctx ->
                val request = ctx.request()
                if (request.method() == HttpMethod.POST && request.path() == STREAM_PATH) {
                    ctx.response()
                        .putHeader(HEADER_AI_GENERATED, "true")
                        .putHeader(HEADER_AI_DISCLOSURE, ENCODED_DISCLOSURE)
                }
                ctx.next()
            },
            FILTER_PRIORITY,
        )
    }

    companion object {
        const val HEADER_AI_GENERATED = "X-AI-Generated"
        const val HEADER_AI_DISCLOSURE = "X-AI-Disclosure"
        private const val STREAM_PATH = "/api/v1/copilot/chat/stream"
        private const val FILTER_PRIORITY = 100

        /** RFC 3986 percent-encoded UTF-8 so the header value is ASCII-safe. */
        val ENCODED_DISCLOSURE: String =
            URLEncoder.encode(AI_DISCLOSURE, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
