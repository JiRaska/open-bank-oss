// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import com.openbank.libs.security.logfmtValue
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
 * Every caller-supplied field goes through [logfmtValue]: a value outside a plain token is quoted
 * and escaped, so no value can end the line, add a field or override one. A structured (JSON) log
 * encoder only protects the line as a whole — this `key=value` payload is still a single
 * `message` string, so the per-field escaping is needed either way.
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
            // `timestamp` is logged explicitly: the log line's own time is when the fallback ran,
            // not when the operation happened, and only the envelope's field survives a replay
            // through a durable publisher (DORA Art. 17 reconstruction).
            "audit event eventId=%s at=%s actor=%s actorType=%s op=%s resource=%s resourceId=%s result=%s traceId=%s",
            event.eventId,
            event.timestamp,
            event.actorId.logfmtValue(),
            event.actorType.logfmtValue(),
            event.operation.logfmtValue(),
            event.resourceType.logfmtValue(),
            event.resourceId.logfmtValue(),
            event.result,
            event.traceId.logfmtValue(),
        )
    }
}
