// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.release-steward")
@ApplicationScoped
interface ReleaseStewardConfig {
    /**
     * Cron for the periodic sweep (ADR-0165).
     *
     * Declared here because it lives under this mapping's prefix: SmallRye validates a
     * `@ConfigMapping` prefix as a CLOSED set, so a key added to `application.yaml` with no
     * matching accessor fails the whole application at boot with `ConfigValidationException` —
     * not a warning about one property, a dead service.
     */
    @WithDefault("0 0 5 * * ?")
    fun checkCron(): String

    @WithDefault("https://api.github.com")
    fun githubApiUrl(): String

    @WithDefault("JiRaska/open-bank-oss")
    fun githubRepo(): String

    @WithDefault("http://litellm.ai-platform:4000")
    fun llmGatewayUrl(): String

    // Filesystem path to the monorepo checkout this agent reads release-please-config.json,
    // .release-please-manifest.json, openbank-admin-ui/package.json and every service
    // application.yaml from (RepoStateReadAdapter). Defaults to the working directory, matching a
    // sidecar/init-container checkout mount in the real deployment.
    @WithDefault(".")
    fun repoRoot(): String
}
