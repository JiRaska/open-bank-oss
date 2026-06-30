// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.temporal

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

/**
 * Configuration for the Temporal-backed payment orchestration (ADR-0120 Phase 5: always-on).
 *
 * `@ConfigMapping` (interface), NOT flat `@ConfigProperty`, so a missing optional key resolves to its
 * `@WithDefault` instead of throwing `SRCFG00040` at boot.
 */
@ConfigMapping(prefix = "openbank.transaction.orchestration.temporal")
@ApplicationScoped
interface TemporalConfig {
    @WithDefault("localhost:7233")
    fun serverUrl(): String

    @WithDefault("openbank-payments")
    fun namespace(): String

    @WithDefault("openbank-payment-execution")
    fun taskQueue(): String
}
