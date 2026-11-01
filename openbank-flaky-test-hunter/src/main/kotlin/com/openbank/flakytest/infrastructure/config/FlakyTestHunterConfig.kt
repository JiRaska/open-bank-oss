// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.Optional

@ConfigMapping(prefix = "openbank.flaky-test-hunter")
@ApplicationScoped
interface FlakyTestHunterConfig {
    @WithDefault("https://api.github.com")
    fun githubApiUrl(): String

    @WithDefault("JiRaska/open-bank-oss")
    fun githubRepo(): String

    /** Fine-grained token limited to contents:write and pull_requests:write on this repository. */
    fun githubToken(): Optional<String>

    @WithDefault("http://litellm.ai-platform:4000")
    fun llmGatewayUrl(): String

    @WithDefault("deepseek-ai/DeepSeek-V3.2")
    fun modelId(): String

    // Filesystem path to a monorepo checkout for TestScanAdapter (the webhook / operator scan
    // path). The DEPLOYED pod mounts no checkout, so there the scan reads an empty tree; the
    // production input is the Admin UI snapshot posted to /evidence/analyze. Keep the default
    // for local and webhook use on a developer machine.
    @WithDefault(".")
    fun repoRoot(): String

    /** ADR-0031 D9 phase 3: policy pre-flight gate on the gh.pr.open write path (PrPathPolicyGate). */
    fun policyGate(): PolicyGate

    interface PolicyGate {
        /** PR writer kill switch. False denies PR writes; true requires both policy layers in every profile. */
        @WithDefault("true")
        fun enabled(): Boolean

        /** Base URL of the OPA sidecar serving the governance bundle. */
        @WithDefault("http://localhost:8181")
        fun opaUrl(): String

        /** Per-call connect + request timeout; exceeding it fails closed (pdp_unreachable). */
        @WithDefault("PT0.5S")
        fun timeout(): Duration
    }
}
