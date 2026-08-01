// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.config

import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.libs.llm.OpenAiCompatibleLlmGatewayClient
import com.openbank.libs.observability.DomainMetrics
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Produces the shared [LlmGatewayPort] (ADR-0174) for devops-agent, wired to the same
 * OpenAI-compatible endpoint + model the gitops config points at (litellm gateway since #2019).
 *
 * The API key is read via an OPTIONAL lookup (not @ConfigProperty), so an un-seeded key degrades the
 * gateway to a deterministic `null` rather than CrashLooping the pod (SmallRye SRCFG00040) — the same
 * safety the previous hand-rolled adapter had. The key is never logged (the client's contract).
 */
@ApplicationScoped
class LlmGatewayProducer {

    @Produces
    @ApplicationScoped
    fun llmGateway(config: DevOpsConfig, metrics: DomainMetrics): LlmGatewayPort {
        val apiKey = ConfigProvider.getConfig()
            .getOptionalValue("devops.model.api-key", String::class.java)
            .orElse("")
        return OpenAiCompatibleLlmGatewayClient(
            baseUrl = config.modelEndpoint(),
            model = config.modelId(),
            apiKey = apiKey,
            metrics = metrics,
        )
    }
}
