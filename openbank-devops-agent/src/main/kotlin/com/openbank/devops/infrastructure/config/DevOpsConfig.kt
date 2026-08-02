// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.devops")
@ApplicationScoped
@Suppress("TooManyFunctions") // flat config mapping: one accessor per tunable (model, GitHub, detector thresholds)
interface DevOpsConfig {
    /**
     * Cron for the daily analysis sweep (ADR-0119).
     *
     * Declared here because it lives under this mapping's prefix: SmallRye validates a
     * `@ConfigMapping` prefix as a CLOSED set, so a key added to `application.yaml` with no
     * matching accessor fails the whole application at boot with `ConfigValidationException` —
     * not a warning about one property, a dead service.
     */
    @WithDefault("0 30 3 * * ?")
    fun analysisCron(): String

    @WithDefault("http://prometheus-operated.observability:9090")
    fun prometheusUrl(): String

    @WithDefault("http://alertmanager-operated.observability:9093")
    fun alertmanagerUrl(): String

    /**
     * Base URL of the OpenAI-compatible model backend. Defaults to DeepInfra — the same provider the
     * customer copilot uses (ADR-0089) — reached at {endpoint}/chat/completions. The API key is read
     * separately via an OPTIONAL lookup (openbank.devops.model.api-key) so an un-seeded key degrades
     * the diagnosis call instead of CrashLooping the pod at boot (SmallRye SRCFG00040 on empty bind).
     */
    @WithDefault("https://api.deepinfra.com/v1/openai")
    fun modelEndpoint(): String

    /** Upstream model name, sent verbatim — the DeepSeek model the copilot already runs on. */
    @WithDefault("deepseek-ai/DeepSeek-V3.2")
    fun modelId(): String

    /**
     * GitHub for remediation-proposal PRs. The token is read separately via an OPTIONAL lookup
     * (devops.github.token ← DEVOPS_GITHUB_TOKEN) so an un-seeded token degrades to "no PR opened"
     * rather than CrashLooping. The agent opens a PR adding a markdown proposal under {proposalDir} —
     * it proposes a document, a human implements; it never writes code or merges (charter ADR-0031).
     */
    @WithDefault("https://api.github.com")
    fun githubApiUrl(): String

    @WithDefault("JiRaska")
    fun githubOwner(): String

    @WithDefault("open-bank")
    fun githubRepo(): String

    @WithDefault("docs/devops-proposals")
    fun githubProposalDir(): String

    /** D1: CI workflow failure rate over 24h that trips a finding (0.20 = 20%). */
    @WithDefault("0.20")
    fun ciFailureRateThreshold(): Double

    /** D2: fleet 5xx ratio (Change Failure Rate proxy) that trips a finding (0.05 = 5%). */
    @WithDefault("0.05")
    fun changeFailureRateThreshold(): Double

    /** D3: ARC assigned/running queue-pressure ratio that trips a WARNING (stranded pool is always CRITICAL). */
    @WithDefault("0.80")
    fun runnerQueuePressureThreshold(): Double

    /** D5: how many OPEN fleet-health issues constitute accumulating SSDLC drift worth flagging. */
    @WithDefault("3")
    fun ssdlcDriftThreshold(): Int

    /** D6: how many times the same critical alert must recur before the learning-loop remediation fires. */
    @WithDefault("3")
    fun incidentRecurrenceThreshold(): Int
}
