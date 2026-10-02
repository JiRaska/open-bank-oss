// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.limits

import com.openbank.risk.infrastructure.LimitsConfig
import com.openbank.risk.infrastructure.toLimitSet
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource

/** The limit set exactly as application.yaml ships it, loaded through [LimitsConfig]. */
object LimitTestSet {
    fun config(): LimitsConfig {
        val url = requireNotNull(LimitTestSet::class.java.classLoader.getResource("application.yaml"))
        return SmallRyeConfigBuilder()
            .withSources(YamlConfigSource(url))
            .withMapping(LimitsConfig::class.java)
            .build()
            .getConfigMapping(LimitsConfig::class.java)
    }

    fun shipped(): LimitSet = config().toLimitSet()
}
