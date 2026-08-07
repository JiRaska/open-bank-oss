// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.config

import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.libs.llm.OpenAiCompatibleLlmGatewayClient
import com.openbank.libs.observability.LlmCallMetrics
import io.quarkus.vertx.ConsumeEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Produces the shared [LlmGatewayPort] (ADR-0174) for flaky-test-hunter, wired to the same LiteLLM
 * gateway endpoint + model the gitops config points at.
 *
 * The API key is read via an OPTIONAL lookup (not @ConfigProperty), so an un-seeded key degrades the
 * gateway to a deterministic `null` rather than CrashLooping the pod (SmallRye SRCFG00040) — the same
 * safety the previous hand-rolled adapter had. The key is never logged.
 */
@ApplicationScoped
class LlmGatewayProducer {

    @Produces
    @ApplicationScoped
    fun llmGateway(config: FlakyTestHunterConfig, metrics: LlmCallMetrics): LlmGatewayPort {
        val apiKey = ConfigProvider.getConfig()
            .getOptionalValue("flaky-test-hunter.model.api-key", String::class.java)
            .orElse("")
        return OpenAiCompatibleLlmGatewayClient(
            baseUrl = config.llmGatewayUrl(),
            model = config.modelId(),
            apiKey = apiKey,
            metrics = metrics,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    @ConsumeEvent("llm-gateway.kill")
    fun onKill(event: String) {
        // No-op; the port is stateless. This listener exists so the kill-switch event bus
        // registration is uniform across agent-plane services (ADR-0031 D7).
    }
}
