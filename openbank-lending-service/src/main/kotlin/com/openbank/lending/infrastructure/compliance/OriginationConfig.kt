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
) {
    companion object {
        const val SANDBOX_ACTOR = "sandbox-auto-approval"
    }
}
