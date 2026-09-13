// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application.port.out

import com.openbank.notification.domain.model.EmailSendOutcome
import com.openbank.notification.domain.model.NotificationTemplate

/**
 * Outbound observability port for the EMAIL channel (issue #4737), the counterpart to
 * [PushMetricsPort] and built for the same reason.
 *
 * The alertable state here is the **success** state. "The mailer is configured and every send is a
 * mock" raises no exception, logs no error and fails no health check — an error rate cannot see it,
 * and neither can absence-of-errors, because there is nothing to be absent. Only an explicit count
 * of the mocked send makes it visible, which is exactly the signal that was missing when the push
 * channel shipped the same defect and a customer had to report it.
 *
 * Naming follows the push port's discipline: **ACCEPTED**, never `delivered`. An SMTP accept is a
 * handoff to a relay, not a receipt from a mailbox, so `delivered` would put a claim in the metric
 * store that no code in this service is able to establish.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays
 * free of the metrics framework and the counters are asserted through a fake in unit tests.
 *
 * Implemented by [com.openbank.notification.infrastructure.observability.EmailMetricsAdapter].
 */
interface EmailMetricsPort {

    /**
     * Record one EMAIL send attempt, tagged by [template] and [outcome].
     *
     * [template] is carried for the same reason the push fan-out carries it: a lost
     * `SCA_APPROVAL` is a PSD2 Art. 97 control failing, and must not be indistinguishable at the
     * metrics layer from a lost marketing message. `NotificationTemplate` is a closed enum, so the
     * label is bounded by construction.
     */
    fun recordSend(template: NotificationTemplate, outcome: EmailSendOutcome)
}
