// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.application

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional

/**
 * Config-driven model registry (ADR-0089 D6). Adding a model is a YAML entry under
 * `copilot.model-gateway.models`, never a code change. Adding a new backend *kind* is a new
 * [ModelProvider] adapter keyed by [ModelEntry.provider].
 *
 * ```yaml
 * copilot:
 *   model-gateway:
 *     default-model: mock-echo
 *     models:
 *       - id: mock-echo            # sandbox: synthetic data only
 *         provider: mock
 *       - id: vllm-cs              # production: in-cluster / EU zero-retention (deferred, FinOps)
 *         provider: openai-compatible
 *         endpoint: http://vllm.copilot.svc:8000
 *         sensitivity: self-hosted
 * ```
 */
@ConfigMapping(prefix = "copilot.model-gateway")
interface ModelGatewayConfig {

    /** Model id used when a chat request does not name one. */
    @WithDefault("mock-echo")
    fun defaultModel(): String

    fun models(): List<ModelEntry>

    interface ModelEntry {
        fun id(): String
        fun provider(): String
        fun endpoint(): Optional<String>

        /** `hosted` (default) | `self-hosted` — drives sensitive-data routing (ADR-0089 D6). */
        @WithDefault("hosted")
        fun sensitivity(): String

        @WithDefault("true")
        fun enabled(): Boolean
    }
}
