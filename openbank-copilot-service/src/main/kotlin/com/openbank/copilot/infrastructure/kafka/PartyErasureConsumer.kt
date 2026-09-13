// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.infrastructure.observability.CopilotMetricsAdapter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.delay
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
 * Vert.x duplicated context and the reactive Panache delete below runs correctly.
 *
 * ## Failure handling — a malformed event and a broken database are not the same thing (#5698)
 *
 * This consumer used to log-and-ack BOTH, which reads as poison-pill safety and is not. A malformed
 * payload is unretryable: replaying it produces the same parse failure forever, so acking it is the
 * only sane outcome and it stays that way below. [ConversationStore.deleteForParty] is a reactive
 * Panache call, and a connection-refused there is the opposite case — the event is fine, the
 * database is not, and the work must still happen once it recovers. Acking that lost a GDPR Art. 17
 * erasure permanently: the only trace was an ERROR line nobody alerts on, and the transcripts the
 * consumer exists to delete stayed on disk while the log claimed the erasure was handled. That is
 * the exact shape that left ten of 73 sandbox parties without a KYC case for months (#5698).
 *
 * So the delete now runs under [withBoundedRetry] and, if it still fails, is RETHROWN — the
 * connector retries and ultimately dead-letters, which is a signal someone can see. The retry is
 * bounded and the failure moves to the DLQ, so a single bad event still cannot wedge the group.
 * [ConversationStore.deleteForParty] is idempotent (a second delete matches nothing), so both the
 * retry and a connector redelivery are safe.
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

    @Inject
    lateinit var metrics: CopilotMetricsAdapter

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

        withBoundedRetry(partyId) {
            val erased = conversationStore.deleteForParty(partyId.toString())
            if (erased == 0L) {
                // Not necessarily a defect — a party that never chatted holds nothing. But it is
                // also the exact shape of an erasure that looked in the wrong place (#4175), and at
                // INFO the two were the same line. The counter is what an alert or a query can read;
                // the WARN is so the party id is greppable when one is investigated.
                metrics.recordPartyErasure(CopilotMetricsAdapter.OUTCOME_NO_MATCH)
                log.warnf(
                    "[party-events-in] GDPR Art. 17: erased 0 copilot conversation(s) for party %s — " +
                        "either the party never chatted, or its rows carry no resolvable party id",
                    partyId,
                )
            } else {
                metrics.recordPartyErasure(CopilotMetricsAdapter.OUTCOME_ERASED)
                log.infof(
                    "[party-events-in] GDPR Art. 17: erased %d copilot conversation(s) for party %s",
                    erased,
                    partyId,
                )
            }
        }
    }

    /**
     * Retry [block] a bounded number of times, then RETHROW so the connector dead-letters.
     *
     * The rethrow is the point. A caught-and-logged failure acks the message, and an acked message
     * that did no work is indistinguishable from one that succeeded — from Kafka, from the consumer
     * lag metric, and from every dashboard built on either. Neither erasure counter is incremented
     * on this path either, so a failed erasure is invisible in the metric as well as in the log.
     */
    @Suppress("TooGenericExceptionCaught") // the retry is type-agnostic on purpose: any failure of the
    // delete is a failure to erase, and the bounded rethrow (not a swallow) is what keeps it visible.
    private suspend fun withBoundedRetry(partyId: UUID, block: suspend () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.errorf(
                        e,
                        "[party-events-in] Erasure for party %s failed after %d attempts (%s: %s) — dead-lettering",
                        partyId,
                        attempt,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    throw e
                }
                log.warnf(
                    "[party-events-in] Erasure for party %s failed (attempt %d/%d, %s: %s) — retrying",
                    partyId,
                    attempt,
                    MAX_ATTEMPTS,
                    e.javaClass.simpleName,
                    e.message,
                )
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 500L
    }
}
