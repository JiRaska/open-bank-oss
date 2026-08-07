// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

/**
 * Default [ExposurePublisher]: logs the exposure on a dedicated category so it is
 * visible without an analytics pipeline, and so an accidentally-shipped default
 * (where a Kafka impl was expected) is grep-able. Mirrors
 * `LoggingAuditEventPublisher`.
 */
class LoggingExposurePublisher : ExposurePublisher {
    private val log = org.jboss.logging.Logger.getLogger("openbank.flags.exposure")

    override suspend fun publish(exposure: FlagExposure) {
        log.infof(
            "flag exposure exposureId=%s at=%s flag=%s variant=%s key=%s reason=%s traceId=%s",
            exposure.exposureId,
            exposure.timestamp,
            exposure.flagKey,
            exposure.variant,
            exposure.targetingKey,
            exposure.reason,
            exposure.traceId,
        )
    }
}
