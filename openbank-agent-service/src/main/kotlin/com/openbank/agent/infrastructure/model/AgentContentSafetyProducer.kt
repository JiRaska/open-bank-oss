// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.model

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
 * Builds agent-service's [ContentSafetyPort] — Llama Guard through the in-cluster LiteLLM gateway
 * (ADR-0174), the same route and the same virtual key the assistant's chat model already uses.
 *
 * Produced here rather than in `openbank-libs-runtime`: a `@Produces` in the shared library drags
 * its dependencies into the Arc type closure of every service that consumes the library, including
 * the ones that never make an LLM call.
 *
 * Disabled by default, and when disabled it produces [ContentSafetyPort.DISABLED] — which reports
 * `UNAVAILABLE` for every classification, never `SAFE`, so an unwired guardrail shows up in
 * `openbank_guardrail_classifications{decision="unavailable"}` instead of looking like a clean bill
 * of health.
 */
@ApplicationScoped
class AgentContentSafetyProducer(
    private val safetyMetrics: ContentSafetyMetricsPort,
    private val callMetrics: LlmCallMetricsPort,
    // No Kotlin defaults on @ConfigProperty parameters — a defaulted one makes Arc build the bean
    // through a synthetic constructor and skip config entirely, so the guardrail could never be
    // switched on whatever the environment said.
    @ConfigProperty(name = "agent.content-safety.enabled", defaultValue = "false")
    private val enabled: Boolean,
    // Optional<String>, not String: a missing plain-typed value throws SRCFG00040 at boot.
    @ConfigProperty(name = "agent.content-safety.endpoint")
    private val endpoint: Optional<String>,
    @ConfigProperty(name = "agent.content-safety.model", defaultValue = "meta-llama/llama-guard-4-12b")
    private val model: String,
    @ConfigProperty(name = "agent.content-safety.api-key")
    private val apiKey: Optional<String>,
) {

    private val log = Logger.getLogger(AgentContentSafetyProducer::class.java)

    @Produces
    @ApplicationScoped
    fun contentSafety(): ContentSafetyPort {
        val url = endpoint.orElse("").trim()
        if (!enabled || url.isEmpty()) {
            log.infof(
                "agent content-safety guardrail DISABLED (enabled=%s, endpoint=%s) — every " +
                    "classification will report 'unavailable'",
                enabled,
                if (url.isEmpty()) "<unset>" else url,
            )
            return ContentSafetyPort.DISABLED
        }
        log.infof("agent content-safety guardrail active — model=%s via %s", model, url)
        return LlamaGuardContentSafetyAdapter(
            baseUrl = url,
            model = model,
            apiKey = apiKey.orElse(""),
            metrics = safetyMetrics,
            callMetrics = callMetrics,
        )
    }
}
