// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application.port.out

import com.openbank.notification.application.OversightSignal
import io.smallrye.mutiny.Uni

/**
 * Outbound port for the oversight webhook side-channel (ADR-0059). The first
 * adapter is Slack; Teams is the same port, a second adapter. Implementations
 * MUST be best-effort and MUST never throw into the notification dispatch path.
 */
interface OversightWebhookPublisher {
    /** Publish an anonymized oversight signal. Returns true if delivered, false if disabled/failed. */
    fun publish(signal: OversightSignal): Uni<Boolean>
}
