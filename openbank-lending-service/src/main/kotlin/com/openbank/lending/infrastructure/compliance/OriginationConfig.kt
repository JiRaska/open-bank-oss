// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.compliance

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Sandbox straight-through origination (ADR-0211 D5). Restart-required, default
 * false, NEVER true in production: when on, a submitted application is driven
 * SUBMITTED → … → READY_TO_DISBURSE by the machine actor `sandbox-auto-approval`
 * (the ADR-0116 STP pattern) so the sandbox runs e2e without an operator.
 */
@ApplicationScoped
class OriginationConfig(
    @param:ConfigProperty(name = "lending.origination.auto-approve", defaultValue = "false")
    val autoApprove: Boolean,
    /**
     * ADR-0314 D5: FLOATING loans can be modelled, stored and published, but nothing reprices one at
     * its reset date yet. Until that engine exists, originating one would book a loan whose rate
     * silently never moves, so the switch stays off and a FLOATING application is a 400.
     */
    @param:ConfigProperty(name = "lending.origination.floating-rate-enabled", defaultValue = "false")
    val floatingRateEnabled: Boolean,
) {
    companion object {
        const val SANDBOX_ACTOR = "sandbox-auto-approval"
    }
}
