// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Whether a `@Scheduled` job's trigger property is switched OFF on this instance.
 *
 * Quarkus never runs a scheduled method whose config-expression trigger resolves to `off` or
 * `disabled`. A job that cannot run must not register a workflow-liveness heartbeat either: the
 * gauge is seeded at registration and only [com.openbank.libs.observability.WorkflowLivenessRecorder.recordSuccess]
 * resets it, so a switched-off job's age grows forever and `WorkflowLivenessStale` fires at 2x its
 * interval on every pod of an instance that deliberately runs without it — the pension company's
 * ledger (ADR-0337), whose bank-only schedulers are all off. Registering nothing is the honest
 * signal: the job is not expected to succeed, so there is no heartbeat to be stale.
 *
 * Reads the SAME property the `@Scheduled` expression names, so an absent property is the
 * annotation's own default cron — on — and no default is duplicated here.
 */
internal object ScheduleSwitch {
    private val OFF_VALUES = setOf("off", "disabled")

    fun isOff(triggerProperty: String, config: Config = ConfigProvider.getConfig()): Boolean =
        config.getOptionalValue(triggerProperty, String::class.java)
            .map { it.trim().lowercase() in OFF_VALUES }
            .orElse(false)
}
