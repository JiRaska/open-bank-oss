// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.observability

import com.openbank.notification.application.port.out.EmailMetricsPort
import com.openbank.notification.domain.model.EmailSendOutcome
import com.openbank.notification.domain.model.NotificationTemplate
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for [EmailMetricsPort] (issue #4737). Emits one series:
 *
 * - `openbank_notification_email_sends_total{template,outcome,service="notification"}`
 *
 * `outcome=ACCEPTED` counts mailer acceptances, not deliveries — see [EmailSendOutcome.ACCEPTED].
 * `outcome=MOCKED` is the signal this adapter exists for: it is a **success**-path counter, in the
 * sense that nothing fails when it increments, which is precisely why nothing could see the mocked
 * channel before. The alert it enables is "the service is sending and every send is a mock", a
 * condition an error rate is structurally unable to express.
 *
 * Both labels are closed enums, so cardinality is bounded by construction — no provider status
 * token, no message body, nothing derived from customer data.
 *
 * Service-local `MeterRegistry`, null-safe via [Instance], exactly like [PushMetricsAdapter]: an
 * email-channel counter is notification-specific, so adding it to the shared libs facade would
 * force a fleet-wide rebuild for a one-service concern.
 */
@ApplicationScoped
class EmailMetricsAdapter(private val registry: MeterRegistry?) : EmailMetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and NotificationConsumer is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun recordSend(template: NotificationTemplate, outcome: EmailSendOutcome) {
        registry?.let { r ->
            Counter.builder("openbank.notification.email.sends")
                .tag("service", SERVICE)
                .tag("template", template.name)
                .tag("outcome", outcome.name)
                .register(r)
                .increment()
        }
    }

    private companion object {
        const val SERVICE = "notification"
    }
}
