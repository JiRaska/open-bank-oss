// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

/**
 * Optional per-service warm-up hook. Any CDI bean implementing this runs after the generic
 * libs-runtime steps and before readiness goes UP, inside the same `openbank.warmup.max-duration`
 * cap and the same per-step isolation (a throw fails that step only).
 *
 * Use it for a hot path the generic steps cannot reach — e.g. a domain calculation on a fixture
 * aggregate. It MUST be side-effect free: no writes, no outbound calls that change remote state.
 */
interface WarmupContributor {
    /** Step name in logs and in the `openbank_warmup_seconds{step=...}` metric. */
    val name: String

    /** Performs the warm-up; returns a short outcome for the log. */
    fun warm(): String
}
