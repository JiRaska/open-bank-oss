// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.application

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional

/**
 * Config-driven model registry (ADR-0031 D6). The whole point: adding a model is a YAML
 * entry under `model-gateway.models`, never a code change. Adding a new backend *kind* is a
 * new [ModelProvider] adapter keyed by [ModelEntry.provider].
 *
 * ```yaml
 * model-gateway:
 *   default-model: mock-echo
 *   models:
 *     - id: mock-echo
 *       provider: mock
 *     - id: claude-sonnet            # add any model — no code change
 *       provider: anthropic
 *       endpoint: https://api.anthropic.com
 *       sensitivity: hosted
 * ```
 */
@ConfigMapping(prefix = "model-gateway")
interface ModelGatewayConfig {

    /** Model id used when a chat request does not name one. */
    @WithDefault("mock-echo")
    fun defaultModel(): String

    fun models(): List<ModelEntry>

    interface ModelEntry {
        fun id(): String
        fun provider(): String
        fun endpoint(): Optional<String>

        /** `hosted` (default) | `self-hosted` — drives sensitive-data routing. */
        @WithDefault("hosted")
        fun sensitivity(): String

        @WithDefault("true")
        fun enabled(): Boolean
    }
}
