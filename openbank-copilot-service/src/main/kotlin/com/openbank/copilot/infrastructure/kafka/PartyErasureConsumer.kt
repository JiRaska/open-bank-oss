// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ConversationStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Handles `PARTY_ERASED` from party-service (GDPR Art. 17 / ADR-0117, ADR-0238) by hard-deleting the
 * erased party's copilot conversation history (#3870).
 *
 * Free-text chat transcripts are exactly where unpredictable personal data lands, and until this
 * consumer existed copilot held them durably in Postgres with no deletion path at all — the 90-day
 * `expires_at` is applied on *read*, so an expired conversation merely stopped being served while
 * its message bodies stayed on disk and in every base backup.
 *
 * Shape is copied from the seven services that already consume this event (notification-service's
 * `PartyErasureConsumer` is the closest sibling): `suspend @Incoming` so Quarkus dispatches on a
 * Vert.x duplicated context and the reactive Panache delete below runs correctly; poison-pill safe,
 * so any parse or delete failure is logged and the message acked rather than wedging the group.
 *
 * ## Identity — why the delete is not keyed on the storage key (#3881)
 *
 * The event carries only `partyId`; copilot keys conversation history on the OIDC `sub`. Those are
 * not the same value: measured against the deployed customers realm, `sub` equalled `party_id` for
 * **0 of 35** users, because `WebAuthnKeycloakClient.ensureUser` never sets the Keycloak user `id`,
 * so Keycloak mints a random UUID and `party_id` survives as a separate attribute. Keyed on `sub`
 * this consumer would receive the event, delete nothing, and log success — a GDPR control reporting
 * coverage it does not have, detectable only by querying the table.
 *
 * It cannot be resolved here either: by erasure time the Keycloak user is gone, so there is nothing
 * left to map `partyId` back to a `sub`. So the identity is captured at WRITE time
 * (`CopilotChatResource.erasureIdentity()` -> `conversation_history.party_id`, migration V2) and
 * [ConversationStore.deleteForParty] matches either column.
 */
@ApplicationScoped
class PartyErasureConsumer {

    @Inject
    lateinit var conversationStore: ConversationStore

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(PartyErasureConsumer::class.java)

    @Suppress("TooGenericExceptionCaught") // a consumer must never die on a single bad/foreign event
    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to parse JSON payload: %.200s", payload)
            return
        }

        if (node.path("eventType").asText() != "PARTY_ERASED") return

        val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull()
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_ERASED without a valid partyId, skipping: %.200s", payload)
            return
        }

        try {
            val erased = conversationStore.deleteForParty(partyId.toString())
            log.infof(
                "[party-events-in] GDPR Art. 17: erased %d copilot conversation(s) for party %s",
                erased,
                partyId,
            )
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to erase copilot conversations for party %s", partyId)
        }
    }
}
