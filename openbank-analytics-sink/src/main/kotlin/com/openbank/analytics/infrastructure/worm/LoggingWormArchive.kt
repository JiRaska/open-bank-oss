// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.worm

import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.WormArchive
import jakarta.enterprise.context.ApplicationScoped
import jakarta.annotation.PostConstruct
import java.util.concurrent.atomic.AtomicReference
import org.jboss.logging.Logger

/**
 * Default [WormArchive] binding: logs each sealed anchor as a structured line and keeps the last one
 * in memory for chaining. **No durable WORM storage** — it makes the service offline-buildable and
 * testable with zero infra, exactly like LoggingAuditEventPublisher / LoggingAnalyticsSink.
 *
 * The production binding (S3 Object Lock in compliance mode, or the audit service) lands as an
 * `@Alternative @Priority(...)` adapter. This one logs at WARN on boot so an operator is never under
 * the illusion that tamper-evidence is durably sealed in this configuration.
 */
@ApplicationScoped
class LoggingWormArchive : WormArchive {

    private val log = Logger.getLogger(LoggingWormArchive::class.java)
    private val last = AtomicReference<IntegrityAnchor?>(null)

    @PostConstruct
    fun warn() {
        log.warn(
            "Using LoggingWormArchive: integrity anchors are logged, NOT sealed to durable WORM storage. " +
                "Bind the S3-Object-Lock adapter in production (ADR-0023 F1+F2)."
        )
    }

    override suspend fun seal(anchor: IntegrityAnchor) {
        last.set(anchor)
        log.infof(
            "WORM anchor sealed (log-only) anchorId=%s merkleRoot=%s prev=%s records=%d source=%s",
            anchor.anchorId, anchor.merkleRoot, anchor.previousAnchorHash ?: "GENESIS",
            anchor.recordCount, anchor.source
        )
    }

    override suspend fun latest(): IntegrityAnchor? = last.get()
}
