// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.application.port.out

import com.openbank.copilot.domain.model.ModelDescriptor
import com.openbank.copilot.domain.model.ModelRequest
import com.openbank.copilot.domain.model.ModelResponse

/**
 * Outbound port for a model backend (ADR-0089 D6). One implementation per *kind* of backend (mock, a
 * hosted public free API, a self-hosted vLLM endpoint, …). `ModelGateway` selects the
 * provider whose [key] matches a [ModelDescriptor.provider], so the rest of the system never
 * depends on a concrete vendor.
 *
 * Implementations must be side-effect-free beyond the network call and must NOT log raw prompt
 * content (PII): the gateway owns audit and prompt hashing.
 *
 * This port is the Apache/AGPL seam (ADR-0031 D8): the Apache-2.0 monorepo keeps only governance plumbing
 * and reference/mock adapters; the production model adapters (in-cluster vLLM, EU zero-retention)
 * live in the separate AGPL-3.0 runtime repo and plug in here.
 */
interface ModelProvider {

    /** Stable provider key matched against `copilot.model-gateway.models[*].provider`, e.g. "mock". */
    val key: String

    suspend fun complete(model: ModelDescriptor, request: ModelRequest): ModelResponse

    /**
     * Stream a completion, calling [onChunk] for each text token as it arrives. Returns the full
     * [ModelResponse] once the stream ends (with tool invocations and usage). [onChunk] is called
     * ONLY for text responses — tool-call rounds produce no visible text, so the callback is silent.
     *
     * The default wraps [complete] and emits the final content word-by-word, which gives the caller
     * something to show without true streaming. Override for real token-by-token latency reduction.
     */
    suspend fun completeStream(
        model: ModelDescriptor,
        request: ModelRequest,
        onChunk: suspend (String) -> Unit,
    ): ModelResponse {
        val response = complete(model, request)
        if (response.toolInvocations.isEmpty() && response.content.isNotBlank()) {
            // Split on spaces, keeping the space at the end of each word.
            response.content.split(Regex("(?<= )")).forEach { word -> onChunk(word) }
        }
        return response
    }
}
