// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardissuance.application.port.`in`.CardUseCase
import com.openbank.cardissuance.application.port.out.CardDelegationProjectionRepository
import com.openbank.cardissuance.domain.model.DelegatedCardGrant
import com.openbank.libs.delegation.DelegationGrantEvent
import com.openbank.libs.delegation.DelegationGrantEventDecoder
import com.openbank.libs.delegation.DelegationGrantEvents
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.delay
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.OffsetDateTime

/**
 * Maintains the local enforcement projection of CARD-scoped delegation grants
 * (ADR-0232 D3). Same failure semantics as the account-service consumer: poison
 * pills acked, transient failures retried then dead-lettered — a swallowed close
 * event would leave a revoked card grant enforceable.
 */
@ApplicationScoped
class CardDelegationEventConsumer(
    private val projectionRepository: CardDelegationProjectionRepository,
    private val cardUseCase: CardUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(CardDelegationEventConsumer::class.java)

    @Incoming("delegation-events-in")
    suspend fun consume(payload: String) {
        val event = DelegationGrantEventDecoder.decode(objectMapper, payload)
        if (event == null) {
            log.warnf("Dropping unprocessable delegation event (poison pill): %.300s", payload)
            return
        }
        withBoundedRetry(event) { dispatch(event) }
    }

    private suspend fun dispatch(event: DelegationGrantEvent) {
        // ADR-0249 D2 runs BEFORE the resourceType filter, and keys on the grant id alone.
        //
        // A "dodatková karta" is authorised by an ACCOUNT-scoped grant (the grantor sharing their
        // account), not a CARD-scoped one — the card did not exist when the grant was written. The
        // filter below drops every non-CARD lifecycle event, so putting this after it would mean
        // the exact revocation that has to kill the card is the one event we never see. The card is
        // found by its own stored delegation_grant_id, which is why no resourceType is needed here.
        if (event.type in CLOSING_TYPES) {
            val accepted = projectionRepository.applyClosed(event.grantId, event.lifecycleRevision)
            if (accepted && event.type in ENDING_TYPES) {
                cardUseCase.blockCardsForRevokedGrant(event.grantId, "$REVOCATION_REASON_PREFIX${event.type}")
            }
        }
        if (event.resourceType != RESOURCE_CARD && event.type in LIFECYCLE_TYPES) return
        when (event.type) {
            in DelegationGrantEvents.ENABLING ->
                if (event.lifecycleRevision != null) upsert(event) else Unit
            in CLOSING_TYPES -> Unit
            else -> Unit
        }
    }

    private suspend fun upsert(event: DelegationGrantEvent) {
        val cardId = event.resourceId ?: return
        projectionRepository.applyActive(
            DelegatedCardGrant(
                id = event.grantId,
                cardId = cardId,
                grantorPartyId = event.grantorPartyId,
                granteePartyId = event.granteePartyId,
                capabilities = event.capabilities,
                validFrom = event.validFrom ?: OffsetDateTime.now(),
                validTo = event.validTo,
                active = true,
            ),
            requireNotNull(event.lifecycleRevision),
        )
    }

    private suspend fun withBoundedRetry(event: DelegationGrantEvent, block: suspend () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt >= MAX_PROJECTION_ATTEMPTS) {
                    log.errorf(
                        e,
                        "delegation event %s/%s failed after %d attempts (%s: %s) — dead-lettering",
                        event.type,
                        event.grantId,
                        attempt,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    throw e
                }
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    private companion object {
        const val RESOURCE_CARD = "CARD"
        const val MAX_PROJECTION_ATTEMPTS = 4
        const val RETRY_BACKOFF_MS = 500L
        const val REVOCATION_REASON_PREFIX = "DELEGATION_"

        /** Events that close the local projection row — the delegate's borrowed controls stop. */
        val CLOSING_TYPES = DelegationGrantEvents.DISABLING

        /**
         * Events that END the authority for good, and therefore end the card it authorised
         * (ADR-0249 D2).
         *
         * `DelegationSuspended` is deliberately absent. A suspension is reversible, so the matching
         * card action would be a reversible freeze — but `DelegationReinstated` would then have to
         * unfreeze, and nothing here can tell a card the BANK froze from one the CUSTOMER froze.
         * Silently unfreezing a card its owner deliberately locked is a worse failure than the one
         * it would fix. A suspended grant still stops the delegate's controls instantly, because
         * the projection row closes below and the edge consults the live grant on every request.
         */
        val ENDING_TYPES = setOf(
            "DelegationRevoked",
            "DelegationRenounced",
            "DelegationExpired",
        )

        val LIFECYCLE_TYPES = setOf(
            "DelegationActivated",
            "DelegationReinstated",
            "DelegationRevoked",
            "DelegationSuspended",
            "DelegationRenounced",
            "DelegationExpired",
        )
    }
}
