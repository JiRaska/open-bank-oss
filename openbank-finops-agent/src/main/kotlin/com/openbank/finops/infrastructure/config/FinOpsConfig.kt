// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finops.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.finops")
@ApplicationScoped
interface FinOpsConfig {
    @WithDefault("http://prometheus-operated.observability:9090")
    fun prometheusUrl(): String

    @WithDefault("http://alertmanager-operated.observability:9093")
    fun alertmanagerUrl(): String

    @WithDefault("http://litellm.ai-platform:4000")
    fun llmGatewayUrl(): String

    @WithDefault("50")
    fun natEgressThresholdGb(): Int

    @WithDefault("3")
    fun nodeChurnThresholdPerHour(): Int
}
