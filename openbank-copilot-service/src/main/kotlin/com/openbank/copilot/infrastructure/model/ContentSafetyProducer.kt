// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.infrastructure.model

import com.openbank.libs.llm.ContentSafetyMetricsPort
import com.openbank.libs.llm.ContentSafetyPort
import com.openbank.libs.llm.LlamaGuardContentSafetyAdapter
import com.openbank.libs.llm.LlmCallMetricsPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Builds the copilot's [ContentSafetyPort] — Llama Guard through the in-cluster LiteLLM gateway
 * (ADR-0174), the model-based half of the ADR-0031 guardrails.
 *
 * The producer lives HERE and not in `openbank-libs-runtime` deliberately: a `@Produces` in the
 * shared runtime library drags its dependencies into the Arc type closure of every service that
 * consumes the library, including the ~30 that never make an LLM call.
 *
 * When `copilot.content-safety.enabled` is false — the default, so no environment starts calling a
 * classifier because it upgraded — this produces [ContentSafetyPort.DISABLED], which reports
 * `UNAVAILABLE` for every classification. Not `SAFE`: an unwired guardrail must be visible in
 * `openbank.guardrail.classifications{decision="unavailable"}`, never look like a clean bill of
 * health.
 *
 * Optional config values are `Optional<String>` rather than plain `String`: a missing plain-typed
 * `@ConfigProperty` throws SRCFG00040 at boot.
 */
@ApplicationScoped
class ContentSafetyProducer(
    private val safetyMetrics: ContentSafetyMetricsPort,
    private val callMetrics: LlmCallMetricsPort,
    // No Kotlin defaults on any of these: a defaulted @ConfigProperty parameter makes Arc build the
    // bean through a synthetic constructor and skip config entirely, so the guardrail would be
    // permanently off no matter what the environment set (`configproperty-kotlin-defaults` gate).
    @ConfigProperty(name = "copilot.content-safety.enabled", defaultValue = "false")
    private val enabled: Boolean,
    // Optional<String>, not String: a missing plain-typed value throws SRCFG00040 at boot.
    @ConfigProperty(name = "copilot.content-safety.endpoint")
    private val endpoint: Optional<String>,
    @ConfigProperty(name = "copilot.content-safety.model", defaultValue = "meta-llama/llama-guard-4-12b")
    private val model: String,
    @ConfigProperty(name = "copilot.content-safety.api-key")
    private val apiKey: Optional<String>,
) {

    private val log = Logger.getLogger(ContentSafetyProducer::class.java)

    @Produces
    @ApplicationScoped
    fun contentSafety(): ContentSafetyPort {
        val url = endpoint.orElse("").trim()
        if (!enabled || url.isEmpty()) {
            log.infof(
                "copilot content-safety guardrail DISABLED (enabled=%s, endpoint=%s) — every " +
                    "classification will report 'unavailable'",
                enabled,
                if (url.isEmpty()) "<unset>" else url,
            )
            return ContentSafetyPort.DISABLED
        }
        log.infof("copilot content-safety guardrail active — model=%s via %s", model, url)
        return LlamaGuardContentSafetyAdapter(
            baseUrl = url,
            model = model,
            apiKey = apiKey.orElse(""),
            metrics = safetyMetrics,
            callMetrics = callMetrics,
        )
    }
}
