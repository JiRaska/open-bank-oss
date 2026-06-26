// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.infrastructure.model

import com.openbank.agent.domain.model.ModelDescriptor
import com.openbank.agent.domain.model.ModelRequest
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Regression guard for the un-seeded-key boot fragility (mirrors copilot #1084): boots the full
 * CDI container with `agent.model.openai.api-key` UNSET (it is `${GROQ_API_KEY:}` — present but
 * empty in the committed config). The key is resolved lazily via ConfigProvider in
 * [OpenAiCompatibleModelProvider], NOT through a non-optional `@ConfigProperty String` binding,
 * which SmallRye rejects as empty at config load (SRCFG00040) and which would CrashLoop the pod /
 * fail every @QuarkusTest. If this regresses to an eager binding, the class fails to *start* — the
 * assertions never run.
 *
 * The profile keeps boot infra-free (no Testcontainers / Keycloak), proving the boot path itself:
 * Flyway migrate-at-start is off so Agroal stays lazy (boot touches no DB), and OIDC early-token
 * acquisition is off so no auth server is contacted. The OIDC resource server is already disabled
 * by the `%test` profile.
 */
@QuarkusTest
@TestProfile(OpenAiCompatibleModelProviderBootTest.NoExternalInfraProfile::class)
class OpenAiCompatibleModelProviderBootTest {

    class NoExternalInfraProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // Force the key empty (present-but-empty == the real un-seeded `${GROQ_API_KEY:}` case),
            // deterministically — a CI/dev env with GROQ_API_KEY exported must NOT mask the scenario.
            // This is also a stronger guard: under this empty override, the old eager
            // @ConfigProperty(String) binding would itself fail config load (SRCFG00040) at boot.
            "agent.model.openai.api-key" to "",
            "quarkus.flyway.migrate-at-start" to "false",
            "quarkus.oidc-client.early-tokens-acquisition" to "false",
        )
    }

    @Inject
    lateinit var provider: OpenAiCompatibleModelProvider

    @Test
    fun `app boots and the provider bean is available with no api-key seeded`() {
        // Reaching here at all means the CDI container started despite the un-seeded key.
        assertThat(provider).isNotNull
        assertThat(provider.key).isEqualTo("openai-compat")
    }

    @Test
    fun `an un-seeded key degrades the call at invocation time, not at boot`() {
        val model = ModelDescriptor(
            id = "llama-3.3-70b-versatile",
            provider = "openai-compat",
            endpoint = "https://api.groq.com/openai/v1",
        )
        // require() in complete() throws IllegalArgumentException — the call degrades, it does not
        // surface a SmallRye config error (which is what an eager binding would have done at boot).
        assertThatThrownBy {
            runBlocking { provider.complete(model, ModelRequest(model = model.id, messages = emptyList())) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("agent.model.openai.api-key is empty")
    }
}
