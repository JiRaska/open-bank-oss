// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * [LoggingAuditEventPublisher] is the DORA Art. 17 fallback that guarantees an audit event
 * is always recorded even when no durable (Kafka) publisher is wired up — so this must never
 * throw regardless of which optional fields are absent.
 */
class AuditEventPublisherTest {

    private val publisher: AuditEventPublisher = LoggingAuditEventPublisher()

    @Test
    fun `publishes a fully populated event without throwing`(): Unit = runBlocking {
        publisher.publish(
            AuditEvent(
                actorId = "party-123",
                actorType = "CUSTOMER",
                operation = "account.party.created",
                resourceType = "account",
                resourceId = "acc-456",
                ipAddress = "203.0.113.7",
                userAgent = "openbank-app/1.0",
                result = AuditResult.SUCCESS,
                traceId = "trace-789",
            ),
        )
    }

    @Test
    fun `publishes an event with absent optional fields without throwing`(): Unit = runBlocking {
        publisher.publish(
            AuditEvent(
                actorId = "system",
                actorType = "SERVICE",
                operation = "payment.sepa.recalled",
                resourceType = "payment",
                resourceId = null,
                result = AuditResult.DENIED,
                traceId = null,
            ),
        )
    }
}
