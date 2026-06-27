// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.erasure

import com.openbank.analytics.application.port.out.CryptoErasure
import com.openbank.libs.analytics.AggregateKey
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Default [CryptoErasure]: logs the erasure intent and reports zero rows. Keeps the service
 * offline-buildable; the real KMS key-destruction adapter is the documented follow-up. The erasure
 * *policy decision* (is this category erasable at all) is fully implemented in RetentionPolicies, so
 * what is stubbed here is only the physical key destruction, never the legal gate.
 */
@ApplicationScoped
class NoOpCryptoErasure : CryptoErasure {

    private val log = Logger.getLogger(NoOpCryptoErasure::class.java)

    override suspend fun erase(key: AggregateKey): Long {
        log.warnf(
            "CryptoErasure no-op: would crypto-shred analytics data for %s/%s. Bind the KMS adapter in production (ADR-0023 F6).",
            key.aggregateType, key.aggregateId
        )
        return 0
    }
}
