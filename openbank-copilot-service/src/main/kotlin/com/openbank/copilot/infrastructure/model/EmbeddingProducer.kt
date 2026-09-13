// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.infrastructure.model

import com.openbank.libs.llm.EmbeddingPort
import com.openbank.libs.llm.LlmCallMetricsPort
import com.openbank.libs.llm.OpenAiCompatibleEmbeddingAdapter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Builds the copilot's [EmbeddingPort] — embeddings through the same LiteLLM gateway as every other
 * LLM call (ADR-0183 §3: embedding egress must inherit the chat path's control, not open a second
 * one).
 *
 * Produced here rather than in `openbank-libs-runtime` for the reason the content-safety producer
 * documents: a `@Produces` in the shared library drags its dependencies into the Arc type closure
 * of every service that consumes the library.
 *
 * Disabled by default, and when disabled it produces [EmbeddingPort.DISABLED] — which returns
 * `null`, so retrieval degrades to keyword-only and the counter says so. It does NOT return a
 * zero vector or an empty list: either would look like a successful embedding of unrelated text and
 * would poison the index with rows that match everything equally.
 */
@ApplicationScoped
class EmbeddingProducer(
    private val callMetrics: LlmCallMetricsPort,
    // No Kotlin defaults on @ConfigProperty parameters — a defaulted one makes Arc build the bean
    // through a synthetic constructor and skip config entirely.
    @ConfigProperty(name = "copilot.retrieval.semantic-enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "copilot.retrieval.embedding-endpoint")
    private val endpoint: Optional<String>,
    @ConfigProperty(name = "copilot.retrieval.embedding-model", defaultValue = "BAAI/bge-m3")
    private val model: String,
    // Must match the migration's `vector(1024)`. Configurable so a model swap is one env change plus
    // one migration, but never silently: OpenAiCompatibleEmbeddingAdapter discards any batch whose
    // width disagrees with this number rather than letting the database reject rows one by one.
    @ConfigProperty(name = "copilot.retrieval.embedding-dimensions", defaultValue = "1024")
    private val dimensions: Int,
    @ConfigProperty(name = "copilot.retrieval.embedding-api-key")
    private val apiKey: Optional<String>,
) {

    private val log = Logger.getLogger(EmbeddingProducer::class.java)

    @Produces
    @ApplicationScoped
    fun embeddings(): EmbeddingPort {
        val url = endpoint.orElse("").trim()
        if (!enabled || url.isEmpty()) {
            log.infof(
                "copilot semantic retrieval DISABLED (enabled=%s, endpoint=%s) — help search is keyword-only",
                enabled,
                if (url.isEmpty()) "<unset>" else url,
            )
            return EmbeddingPort.DISABLED
        }
        log.infof("copilot semantic retrieval active — model=%s (%d dims) via %s", model, dimensions, url)
        return OpenAiCompatibleEmbeddingAdapter(
            baseUrl = url,
            model = model,
            dimensions = dimensions,
            apiKey = apiKey.orElse(""),
            metrics = callMetrics,
        )
    }
}
