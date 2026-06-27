// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.infrastructure.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

@ConfigMapping(prefix = "openbank.devops")
@ApplicationScoped
interface DevOpsConfig {
    @WithDefault("http://prometheus-operated.observability:9090")
    fun prometheusUrl(): String

    @WithDefault("http://alertmanager-operated.observability:9093")
    fun alertmanagerUrl(): String

    @WithDefault("http://litellm.ai-platform:4000")
    fun llmGatewayUrl(): String

    /** D1: CI workflow failure rate over 24h that trips a finding (0.20 = 20%). */
    @WithDefault("0.20")
    fun ciFailureRateThreshold(): Double

    /** D2: fleet 5xx ratio (Change Failure Rate proxy) that trips a finding (0.05 = 5%). */
    @WithDefault("0.05")
    fun changeFailureRateThreshold(): Double

    /** D3: ARC assigned/running queue-pressure ratio that trips a WARNING (stranded pool is always CRITICAL). */
    @WithDefault("0.80")
    fun runnerQueuePressureThreshold(): Double

    /** D6: how many times the same critical alert must recur before the learning-loop remediation fires. */
    @WithDefault("3")
    fun incidentRecurrenceThreshold(): Int
}
