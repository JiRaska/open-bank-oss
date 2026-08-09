// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.observability

import com.openbank.notification.application.port.out.PushMetricsPort
import com.openbank.notification.domain.model.NotificationOutcome
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.PushSendOutcome
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for [PushMetricsPort] (ADR-0252 phase 0). Emits two series:
 *
 * - `openbank_notification_push_sends_total{platform,outcome,error_code,service="notification"}`
 * - `openbank_notification_push_fanouts_total{outcome,devices_bucket,service="notification"}`
 *
 * `outcome=ACCEPTED` counts provider acceptances, not deliveries — see [PushSendOutcome.ACCEPTED].
 * The alert this is built for is the one that was missing: `ACCEPTED` sitting at zero while the
 * service is otherwise healthy, and `SKIPPED` being non-zero in an environment that is supposed to
 * push. Neither was expressible before, because the channel emitted no metric.
 *
 * `error_code` is a provider status token (`HTTP_410`, `BadDeviceToken`, `CONFIG`), never a message
 * body: adapters already truncate provider bodies, and a body must not become a metric label.
 * Cardinality is bounded by [MAX_ERROR_CODE_LENGTH] and by the fact that the adapters emit a fixed
 * vocabulary; anything longer is folded to `other`.
 *
 * Device counts are bucketed rather than tagged verbatim — a party can hold an unbounded number of
 * devices, and a raw count would make the label set unbounded with it.
 *
 * Service-local `MeterRegistry`, null-safe via [Instance], exactly like `FraudMetricsAdapter` and
 * libs `DomainMetrics`: a push-channel counter is notification-specific, so adding it to the shared
 * libs facade would force a fleet-wide rebuild for a one-service concern.
 */
@ApplicationScoped
class PushMetricsAdapter(private val registry: MeterRegistry?) : PushMetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and NotificationConsumer is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun recordSend(platform: PushPlatform, outcome: PushSendOutcome, errorCode: String?) {
        registry?.let { r ->
            Counter.builder("openbank.notification.push.sends")
                .tag("service", SERVICE)
                .tag("platform", platform.name)
                .tag("outcome", outcome.name)
                .tag("error_code", normalizeErrorCode(errorCode))
                .register(r)
                .increment()
        }
    }

    override fun recordFanOut(outcome: NotificationOutcome, devices: Int) {
        registry?.let { r ->
            Counter.builder("openbank.notification.push.fanouts")
                .tag("service", SERVICE)
                .tag("outcome", outcome.name)
                .tag("devices_bucket", deviceBucket(devices))
                .register(r)
                .increment()
        }
    }

    companion object {
        private const val SERVICE = "notification"

        /** Longer than any token the APNs/FCM adapters produce; anything else is a body, not a code. */
        private const val MAX_ERROR_CODE_LENGTH = 40

        private const val FEW_DEVICES = 1
        private const val SEVERAL_DEVICES = 3

        /** `none` for the success case, so the label is always present and never empty. */
        fun normalizeErrorCode(errorCode: String?): String {
            val code = errorCode?.trim().orEmpty()
            return when {
                code.isEmpty() -> "none"
                code.length > MAX_ERROR_CODE_LENGTH -> "other"
                else -> code
            }
        }

        /** Buckets, not the raw count: a party's device count is unbounded and so would the labels be. */
        fun deviceBucket(devices: Int): String = when {
            devices <= 0 -> "0"
            devices <= FEW_DEVICES -> "1"
            devices <= SEVERAL_DEVICES -> "2-3"
            else -> "4+"
        }
    }
}
