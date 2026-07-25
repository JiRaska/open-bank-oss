// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A REAL CDI bean that replaces [com.openbank.libs.audit.LoggingAuditEventPublisher] for the
 * `@QuarkusTest`s, rather than a mock handed to a constructor.
 *
 * The failure this guards against is a wiring failure — the endpoint not being given a publisher,
 * or the `McpCallAuditor` never being resolved — and a constructor-injected mock cannot see that,
 * because the test builds the graph itself. Here the container builds it, so an event landing in
 * [events] proves the deployed wiring emits.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class RecordingAuditEventPublisher : AuditEventPublisher {

    val events: MutableList<AuditEvent> = CopyOnWriteArrayList()

    override suspend fun publish(event: AuditEvent) {
        events.add(event)
    }

    fun clear() = events.clear()
}
