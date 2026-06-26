// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finops.infrastructure.temporal

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.temporal")
@ApplicationScoped
interface TemporalConfig {
    @WithDefault("false")
    fun enabled(): Boolean

    @WithDefault("localhost:7233")
    fun serverUrl(): String

    @WithDefault("openbank")
    fun namespace(): String

    @WithDefault("finops-agent")
    fun taskQueue(): String
}
