// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.model

import com.openbank.agent.application.ModelGatewayConfig
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test

/**
 * ADR-0174/0175 (#1919): the hosted model backend must be repointable at the in-cluster LiteLLM
 * gateway by **environment alone** — no image rebuild, no code change — so the gateway can be the
 * single pod allowed to egress to a US LLM provider.
 *
 * This guards the two config seams the GitOps manifest drives:
 *  - `AGENT_MODEL_ENDPOINT` overrides the `llama-3.3-70b-versatile` entry's base URL (it is
 *    `${AGENT_MODEL_ENDPOINT:http://litellm.ai-platform.svc:4000/v1}` in the committed config). Without the
 *    placeholder the endpoint is baked into the image and repointing is impossible from GitOps.
 *  - `AGENT_MODEL_API_KEY` overrides `agent.model.openai.api-key`, which is
 *    `${AGENT_MODEL_API_KEY:${GROQ_API_KEY:}}` — a *nested* default, so a deploy still on a
 *    pre-#1919 image (or a local dev shell) that only exports GROQ_API_KEY keeps working. If that
 *    nesting ever stops expanding, the property resolves to the literal expression text and the
 *    gateway would answer 401 — silently. The assertion below is against the resolved value, so a
 *    non-expanding expression fails the test rather than shipping.
 *
 * Profile keeps boot infra-free (same rationale as [OpenAiCompatibleModelProviderBootTest]).
 */
@QuarkusTest
@TestProfile(ModelGatewayRoutingOverrideTest.GatewayRoutedProfile::class)
class ModelGatewayRoutingOverrideTest {

    class GatewayRoutedProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Exactly what openbank-infra/gitops/components/agent/agent-service.yaml sets.
            "AGENT_MODEL_ENDPOINT" to GATEWAY_URL,
            "AGENT_MODEL_API_KEY" to VIRTUAL_KEY,
            "quarkus.flyway.migrate-at-start" to "false",
            "quarkus.oidc-client.early-tokens-acquisition" to "false",
        )
    }

    @Inject
    lateinit var modelGateway: ModelGatewayConfig

    @Test
    fun `AGENT_MODEL_ENDPOINT repoints the hosted model at the in-cluster gateway`() {
        val hosted = modelGateway.models().single { it.id() == "llama-3.3-70b-versatile" }

        assertThat(hosted.endpoint()).contains(GATEWAY_URL)
        assertThat(hosted.provider()).isEqualTo("openai-compat")
        // The id is sent verbatim upstream, so litellm-config must map this exact name.
        assertThat(hosted.id()).isEqualTo("llama-3.3-70b-versatile")
    }

    @Test
    fun `AGENT_MODEL_API_KEY resolves as the backend key and expands to a real value`() {
        val key = ConfigProvider.getConfig()
            .getOptionalValue("agent.model.openai.api-key", String::class.java).orElse("")

        assertThat(key).isEqualTo(VIRTUAL_KEY)
        // A non-expanding nested default would leave the raw `${...}` text here.
        assertThat(key).doesNotContain("\${")
    }

    private companion object {
        const val GATEWAY_URL = "http://litellm.ai-platform.svc:4000/v1"
        const val VIRTUAL_KEY = "sk-test-virtual-key"
    }
}
