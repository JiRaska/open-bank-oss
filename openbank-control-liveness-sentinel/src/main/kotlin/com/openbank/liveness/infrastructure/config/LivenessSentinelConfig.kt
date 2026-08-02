// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.liveness-sentinel")
@ApplicationScoped
@Suppress("TooManyFunctions") // flat config mapping: one accessor per tunable (model, GitHub, thresholds)
interface LivenessSentinelConfig {
    /**
     * Cron for the daily fleet-wide check (ADR-0163: "runs daily ... plus reactively").
     *
     * Declared here because it lives under this mapping's prefix: SmallRye validates a
     * `@ConfigMapping` prefix as a CLOSED set, so a key added to `application.yaml` without a
     * matching accessor fails the whole application with `ConfigValidationException` at boot --
     * not a warning about one property, a dead service.
     */
    @WithDefault("0 15 3 * * ?")
    fun checkCron(): String

    @WithDefault("http://prometheus-operated.observability:9090")
    fun prometheusUrl(): String

    @WithDefault("http://alertmanager-operated.observability:9093")
    fun alertmanagerUrl(): String

    /**
     * Base URL of the OpenAI-compatible model backend. Defaults to DeepInfra — the same provider
     * devops-agent (ADR-0119) and the customer copilot (ADR-0089) already run against in production,
     * NOT the "litellm.ai-platform" gateway every sibling agent's charter names: that in-cluster
     * gateway is aspirational (ADR-0031 D6) and is not actually deployed anywhere in this repo's
     * gitops (verified: no LiteLLM Deployment/Service manifest exists, only policy-comment mentions).
     * The API key is read separately via an OPTIONAL lookup (liveness.model.api-key)
     * so an un-seeded key degrades the diagnosis call instead of CrashLooping the pod at boot
     * (SmallRye SRCFG00040 on an empty String bind) — mirrors devops-agent's DevOpsConfig exactly.
     */
    @WithDefault("https://api.deepinfra.com/v1/openai")
    fun modelEndpoint(): String

    /** Upstream model name, sent verbatim — the same DeepSeek model devops-agent/copilot already run. */
    @WithDefault("deepseek-ai/DeepSeek-V3.2")
    fun modelId(): String

    /**
     * GitHub for opening tracking tickets / rare mechanical-fix PRs. The token is read separately via
     * an OPTIONAL lookup (liveness.github.token) so an un-seeded token degrades to
     * "no ticket/PR opened" rather than CrashLooping — mirrors devops-agent's RemediationProposalAdapter.
     */
    @WithDefault("https://api.github.com")
    fun githubApiUrl(): String

    @WithDefault("JiRaska")
    fun githubOwner(): String

    @WithDefault("open-bank-oss")
    fun githubRepo(): String

    @WithDefault("docs/liveness-sentinel-proposals")
    fun githubProposalDir(): String

    // Matches ADR-0160 mechanism 3's own Alertmanager paging threshold (2x expected interval) —
    // this agent's CRITICAL finding and the underlying page can never silently disagree.
    @WithDefault("2.0")
    fun staleHeartbeatMultiplier(): Double

    // Fires a WARNING before the heartbeat reaches the paging threshold, so a control that is
    // merely getting stale is visible before it becomes a page (ADR-0163's own worked example).
    @WithDefault("1.5")
    fun warnHeartbeatMultiplier(): Double

    @WithDefault("3")
    fun consecutiveDriftThreshold(): Int
}
