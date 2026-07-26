// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped

/**
 * The fleet-wide Temporal client configuration contract (ADR-0209 D1, issue #2572).
 *
 * Replaces 10 per-service copies of this interface. Every property carries a permissive
 * `@WithDefault` so that no service can fail to boot with `SRCFG00014` on a key it never set;
 * the per-service values that used to live in the copied `@WithDefault`s are now written
 * EXPLICITLY into each service's `application.yaml`. That is deliberate: a shared contract cannot
 * carry 14 different defaults, and a default that varies per consumer is config pretending to be
 * code. `OPENBANK_TEMPORAL_*` env vars (what the GitOps manifests actually set) override both.
 */
@ConfigMapping(prefix = "openbank.temporal")
@ApplicationScoped
interface TemporalConfig {
    /** Whether this service registers a Temporal worker at boot. */
    @WithDefault("false")
    fun enabled(): Boolean

    /** `host:port` of the Temporal frontend. */
    @WithDefault("localhost:7233")
    fun serverUrl(): String

    /** Temporal namespace this service's workflows live in. */
    @WithDefault("openbank")
    fun namespace(): String

    /** Task queue this service's worker polls. */
    @WithDefault("openbank")
    fun taskQueue(): String

    /**
     * Whether the Temporal client reports its gRPC metrics into the Micrometer registry.
     *
     * Preserves the ONE genuine behavioural divergence found among the extracted copies:
     * openbank-devops-agent's producer built its service stubs with no metrics scope at all, while
     * the other 13 attached a `MicrometerClientStatsReporter`. Folding that difference away
     * silently would have started emitting a new metric series for a service whose copy
     * deliberately (or accidentally) had none — so it is expressed as config instead.
     */
    @WithDefault("true")
    fun metricsEnabled(): Boolean
}
