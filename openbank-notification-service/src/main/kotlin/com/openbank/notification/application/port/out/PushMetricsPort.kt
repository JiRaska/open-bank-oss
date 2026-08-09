// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application.port.out

import com.openbank.notification.domain.model.NotificationOutcome
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.PushSendOutcome

/**
 * Outbound observability port for the PUSH channel (ADR-0002 / ADR-0252 phase 0).
 *
 * Until this port existed the PUSH fan-out emitted **no metric at all** — across
 * `openbank-notification-service/src/main` not one class touched `MeterRegistry` — so a push
 * channel that had stopped delivering produced exactly the same telemetry as a healthy one, and
 * the outage was reported by a customer. There was nothing to alert on because there was no
 * series.
 *
 * The vocabulary is deliberate. A send is **ACCEPTED**, never "delivered": APNs answers HTTP 200
 * to mean *accepted for delivery* and issues no delivery receipt, so whether a device received
 * anything is not observable from this process. Closing that loop needs a device-side
 * acknowledgement — ADR-0252 phase 3, tracked in #4348. Naming the counter `delivered` here would
 * put a claim in the metric store that no code in this service is able to establish.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays
 * free of the metrics framework and the counters are asserted through a fake in unit tests.
 *
 * Implemented by [com.openbank.notification.infrastructure.observability.PushMetricsAdapter].
 */
interface PushMetricsPort {

    /** Record one send to one device, tagged by [platform], [outcome] and provider [errorCode]. */
    fun recordSend(platform: PushPlatform, outcome: PushSendOutcome, errorCode: String?)

    /**
     * Record the terminal [outcome] of one notification's fan-out over [devices] device tokens.
     *
     * Emitted for every fan-out including the zero-device case, so "nobody has a device
     * registered" is a visible number rather than an absence.
     *
     * [template] is carried because not every push is worth the same alert. A lost
     * `SCA_APPROVAL` is the prompt telling a customer a payment waits on their approval — a
     * PSD2 Art. 97 control — and without this label it is indistinguishable at the metrics
     * layer from a lost marketing message. `NotificationTemplate` is a closed enum, so the
     * label is bounded by construction and cannot become a cardinality problem.
     */
    fun recordFanOut(template: NotificationTemplate, outcome: NotificationOutcome, devices: Int)
}
