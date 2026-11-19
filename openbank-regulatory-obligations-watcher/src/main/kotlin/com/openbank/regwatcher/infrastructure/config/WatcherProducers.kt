// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.config

import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.libs.llm.OpenAiCompatibleLlmGatewayClient
import com.openbank.libs.observability.LlmCallMetrics
import com.openbank.regwatcher.application.WatchRunService
import com.openbank.regwatcher.application.WatcherLimits
import com.openbank.regwatcher.application.port.DraftTicketPolicyPort
import com.openbank.regwatcher.application.port.GovernanceIssuePort
import com.openbank.regwatcher.application.port.ObligationAssessorPort
import com.openbank.regwatcher.application.port.ObligationRegisterPort
import com.openbank.regwatcher.application.port.WatcherStatePort
import com.openbank.regwatcher.application.port.WebFetchPort
import com.openbank.regwatcher.infrastructure.github.GitHubIssueAdapter
import com.openbank.regwatcher.infrastructure.llm.LlmObligationAssessor
import com.openbank.regwatcher.infrastructure.policy.OpaDraftTicketPolicy
import com.openbank.regwatcher.infrastructure.register.PackagedObligationRegister
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/** Wires the framework-free [WatchRunService] to its adapters. */
@ApplicationScoped
class WatcherProducers {
    @Produces
    @ApplicationScoped
    fun register(config: WatcherConfig): ObligationRegisterPort =
        PackagedObligationRegister.fromClasspath(config.register().resource())

    /**
     * The gateway, keyed to the charter's model id. An unseeded key degrades to "no model answer"
     * (every change is deferred and retried), never to a boot failure.
     */
    @Produces
    @ApplicationScoped
    fun llmGateway(config: WatcherConfig, metrics: LlmCallMetrics): LlmGatewayPort = OpenAiCompatibleLlmGatewayClient(
        baseUrl = config.model().gatewayUrl(),
        model = config.model().id(),
        apiKey = config.model().apiKey().orElse(""),
        metrics = metrics,
    )

    @Produces
    @ApplicationScoped
    fun assessor(config: WatcherConfig, gateway: LlmGatewayPort): ObligationAssessorPort = LlmObligationAssessor(
        gateway = gateway,
        modelId = config.model().id(),
        systemPrompt = loadPrompt(),
        maxContentChars = config.model().maxContentChars(),
    )

    /** Produced as the PORT type, so a test replaces it with QuarkusMock at the port. */
    @Produces
    @ApplicationScoped
    fun issues(config: WatcherConfig): GovernanceIssuePort = GitHubIssueAdapter(config)

    @Produces
    @ApplicationScoped
    fun draftTicketPolicy(config: WatcherConfig): DraftTicketPolicyPort = OpaDraftTicketPolicy(config)

    /** @Singleton: a concrete class needs no client proxy, and nothing replaces it in tests. */
    @Produces
    @Singleton
    @Suppress("LongParameterList")
    fun runService(
        config: WatcherConfig,
        register: ObligationRegisterPort,
        web: WebFetchPort,
        assessor: ObligationAssessorPort,
        issues: GovernanceIssuePort,
        policy: DraftTicketPolicyPort,
        state: WatcherStatePort,
    ): WatchRunService = WatchRunService(
        register,
        web,
        assessor,
        issues,
        policy,
        state,
        WatcherLimits(config.tokensPerRun(), config.runsPerDay(), config.maxIssuesPerRun()),
    ).also { it.killed = !config.enabled() }

    private fun loadPrompt(): String {
        val path = "/governance-prompts/regulatory-obligations-watcher/system.v1.md"
        return WatcherProducers::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("prompt registry resource missing: $path — packaged by build.gradle.kts (ADR-0148)")
    }
}
