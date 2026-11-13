// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * Ongoing-monitoring configuration (ADR-0376). [enabled] is false by default: nothing is
 * registered, matched or published until an environment switches it on.
 *
 * The bounds are what make both paths rate-bounded: a tick matches at most
 * [deltaMaxEntriesPerRun] changed entries and sweeps at most [sweepBatchSize] subjects.
 */
@ApplicationScoped
class SanctionsMonitoringSettings(
    @ConfigProperty(name = "openbank.sanctions.monitoring.enabled", defaultValue = "false")
    val enabled: Boolean,
    @ConfigProperty(name = "openbank.sanctions.monitoring.delta.max-entries-per-run", defaultValue = "5")
    val deltaMaxEntriesPerRun: Int,
    /** Above this backlog the delta is abandoned for a full re-sweep, which costs less. */
    @ConfigProperty(name = "openbank.sanctions.monitoring.delta.max-pending", defaultValue = "20000")
    val deltaMaxPending: Long,
    /** An entry matching more subjects than this is handed to the sweep instead. */
    @ConfigProperty(name = "openbank.sanctions.monitoring.delta.max-candidates-per-entry", defaultValue = "5000")
    val deltaMaxCandidatesPerEntry: Int,
    @ConfigProperty(name = "openbank.sanctions.monitoring.sweep.batch-size", defaultValue = "25")
    val sweepBatchSize: Int,
    /** How often every subject is fully re-evaluated, whatever the deltas did. */
    @ConfigProperty(name = "openbank.sanctions.monitoring.sweep.interval", defaultValue = "P30D")
    val sweepInterval: Duration,
    /** How long a claimed subject or queue row stays claimed before another run may take it. */
    @ConfigProperty(name = "openbank.sanctions.monitoring.claim-lease", defaultValue = "PT15M")
    val claimLease: Duration,
)
