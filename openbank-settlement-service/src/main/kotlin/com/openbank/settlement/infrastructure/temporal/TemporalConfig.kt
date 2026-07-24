// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.temporal

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.temporal")
@ApplicationScoped
interface TemporalConfig {
    @WithDefault("localhost:7233")
    fun serverUrl(): String

    @WithDefault("openbank-settlement")
    fun namespace(): String

    @WithDefault("openbank-settlement")
    fun taskQueue(): String
}
