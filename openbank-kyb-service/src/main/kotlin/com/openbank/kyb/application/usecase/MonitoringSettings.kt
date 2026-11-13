// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * Configuration of ongoing-monitoring registration (ADR-0376, kyb side).
 *
 * [enabled] is false by default and gates exactly one thing: whether the relay talks to the
 * monitored-subject registry. The desired state is recorded with every case write whatever the
 * switch says, and a finding about a subject this service registered is handled whatever the
 * switch says, so turning it on later loses nothing and turning it off drops nothing.
 */
@ApplicationScoped
class MonitoringSettings(
    @ConfigProperty(name = "openbank.kyb.monitoring.enabled", defaultValue = "false")
    val enabled: Boolean,
    /** Rows sent, cases backfilled and rows re-asserted per run: the relay's rate bound. */
    @ConfigProperty(name = "openbank.kyb.monitoring.relay.batch-size", defaultValue = "50")
    val batchSize: Int,
    /**
     * How long a confirmed registration is trusted before it is sent again. Re-registering is
     * idempotent on the registry's side, so this is the repair path after the registry lost
     * state: lower it to re-assert the whole book at the relay's pace, then restore it.
     */
    @ConfigProperty(name = "openbank.kyb.monitoring.reassert-after", defaultValue = "P7D")
    val reassertAfter: Duration,
    /**
     * The client this service calls the registry as, which is the owner the registry records and
     * the `ownerService` its findings carry. Read from the client configuration itself so the
     * two cannot be set apart.
     */
    @ConfigProperty(name = "quarkus.oidc-client.m2m.client-id")
    val ownerClientId: String,
)
