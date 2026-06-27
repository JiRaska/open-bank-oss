// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default
import org.jboss.logging.Logger

interface AuditEventPublisher {
    suspend fun publish(event: AuditEvent)
}

/**
 * Default [AuditEventPublisher] that logs the event as a single structured line.
 *
 * The output JSON is shaped to be ingestable by openbank-audit-service via log-pipeline
 * (Fluent Bit / Vector) if the Kafka publisher isn't yet wired up. Services that want
 * durable delivery should provide their own `@Alternative` `@Priority(...)` Kafka-based
 * implementation:
 *
 *     @ApplicationScoped @Alternative @Priority(100)
 *     class KafkaAuditEventPublisher(
 *         @Channel("audit-events-out") private val emitter: Emitter<Record<String, String>>
 *     ) : AuditEventPublisher { ... }
 *
 * This logging fallback exists so a missing Kafka topic doesn't silently drop audit
 * events — DORA Art. 17 requires reconstruction within 24h, so something must always
 * record the event.
 */
@ApplicationScoped
@Default
class LoggingAuditEventPublisher : AuditEventPublisher {
    private val log = Logger.getLogger("openbank.audit")

    override suspend fun publish(event: AuditEvent) {
        log.infof(
            "audit event eventId=%s actor=%s actorType=%s op=%s resource=%s resourceId=%s result=%s traceId=%s",
            event.eventId,
            event.actorId,
            event.actorType,
            event.operation,
            event.resourceType,
            event.resourceId ?: "-",
            event.result,
            event.traceId ?: "-",
        )
    }
}
