// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.application.port.out

import com.openbank.communication.domain.Persona
import com.openbank.communication.domain.PersonaPublishOutcome
import com.openbank.communication.domain.PublishedStyle
import com.openbank.communication.domain.StyleVersion
import java.time.Instant
import java.util.UUID

interface PersonaRepository {
    suspend fun findByKey(key: String): Persona?
    suspend fun find(id: UUID): Persona?
}

interface StyleVersionRepository {
    suspend fun create(styleVersion: StyleVersion): StyleVersion
    suspend fun find(id: UUID): StyleVersion?
    suspend fun latestVersionNumber(personaId: UUID): Int
    suspend fun submit(id: UUID, at: Instant): StyleVersion?
    suspend fun publish(id: UUID, checker: String, at: Instant): StyleVersion?
    suspend fun retire(id: UUID, checker: String, at: Instant): StyleVersion?
    suspend fun findPublished(personaId: UUID): StyleVersion?
}

interface CommunicationAuditRepository {
    suspend fun append(type: String, aggregateId: UUID, actor: String, details: String, at: Instant)
}

/**
 * Outbox port for `communication.persona.published.v1` (ADR-0285 D5, ADR-0003). Consumers
 * (copilot-service first) refresh their cached [PublishedStyle] on this event rather than
 * polling; the short-TTL cache is the fallback path when delivery is delayed or the consumer
 * was down when the event fired.
 */
interface CommunicationEventPublisher {
    /**
     * Hands the event to the transport and reports what actually happened. Callers must branch
     * on the returned [PersonaPublishOutcome] — there is deliberately no boolean, so an unwired
     * adapter cannot be mistaken for a delivering one.
     */
    suspend fun publishPersonaPublished(personaKey: String, published: PublishedStyle): PersonaPublishOutcome
}
