// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardprocessing.application.port.`in`.CardTokenUseCase
import com.openbank.cardprocessing.application.port.`in`.ChangeTokenStatusCommand
import com.openbank.cardprocessing.application.port.`in`.ProvisionTokenCommand
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardTokenRegistrationRepository
import com.openbank.cardprocessing.domain.event.CardLifecycleEvent
import com.openbank.cardprocessing.domain.event.CardTokenProvisioned
import com.openbank.cardprocessing.domain.event.CardTokenStatusChanged
import com.openbank.cardprocessing.domain.model.CardTokenRegistration
import com.openbank.cardprocessing.domain.model.TokenOutcome
import com.openbank.cardprocessing.domain.model.TokenReadSource
import com.openbank.cardprocessing.domain.model.TokenRefusal
import com.openbank.cardprocessing.domain.model.TokenRegistrations
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkToken
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.cards.scheme.TokenRequestor
import com.openbank.libs.domain.cards.scheme.TokenisationPort
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Provisioning and lifecycle for network tokens — the caller ADR-0283 phase 2 did not have.
 *
 * ## The network owns the vault; this service owns the record
 *
 * Every write goes to the scheme first and is mirrored only once the scheme answered. The reverse
 * order would produce rows for tokens that do not exist, and the mirror's whole value is that it can
 * be trusted as a record of what the network confirmed.
 *
 * Reads are the other way round and deliberately asymmetric: a network that cannot answer must not
 * turn a screen blank, so the mirror answers instead — labelled
 * [LOCAL_MIRROR][TokenReadSource.LOCAL_MIRROR], never presented as live. The rule this follows is
 * the one the notification fan-out broke: a degraded outcome may not share a signal with a good one
 * (ADR-0252 phase 0, #4348).
 *
 * ## Idempotency
 *
 * Provisioning is not naturally idempotent — asking twice mints two tokens, and a wallet that
 * retried a timed-out request would end up with a duplicate credential the customer can see. The
 * caller's `Idempotency-Key` is therefore checked BEFORE the scheme call and is held by a UNIQUE
 * index on the row, so a retry returns the first registration instead of provisioning again.
 */
@ApplicationScoped
class CardTokenService(
    private val tokenisation: TokenisationPort,
    private val registrations: CardTokenRegistrationRepository,
    private val cards: CardLookupPort,
    private val metrics: CardLifecycleMetricsPort,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : CardTokenUseCase {

    private val log = Logger.getLogger(CardTokenService::class.java)

    override suspend fun provision(command: ProvisionTokenCommand): TokenOutcome {
        registrations.findByIdempotencyKey(command.idempotencyKey)?.let { return TokenOutcome.Provisioned(it) }

        // The card must be one this bank issued before its credential is put in a wallet. The lookup
        // fails CLOSED in its adapter (ISSUER_UNAVAILABLE), so an unreachable card-issuance is a
        // refusal here rather than a token minted against a card nobody could confirm.
        if (cards.lookup(command.cardId) == null) {
            metrics.tokenProvisioned("UNKNOWN", TokenRefusal.CARD_NOT_FOUND.name)
            return TokenOutcome.Refused(TokenRefusal.CARD_NOT_FOUND, "no card ${command.cardId}")
        }

        val requestor = TokenRequestor(command.requestorId, command.requestorLabel)
        return when (val answer = tokenisation.provision(command.cardId.toString(), requestor)) {
            is SchemeResult.Answered -> {
                val now = Instant.now(clock)
                val registration = CardTokenRegistration(
                    // UUIDv7 (ADR-0106): a durable, indexed primary key. `Ids.randomId()` is for
                    // idempotency and correlation values, which this is not.
                    id = Ids.newId(),
                    cardId = command.cardId,
                    tokenReference = answer.value.tokenReference,
                    requestorId = command.requestorId,
                    requestorLabel = command.requestorLabel,
                    last4 = answer.value.last4,
                    status = answer.value.status,
                    scheme = answer.scheme,
                    expiry = answer.value.expiry,
                    provisionedAt = now,
                    updatedAt = now,
                )
                val event = CardTokenProvisioned(
                    registrationId = registration.id,
                    cardId = registration.cardId,
                    tokenReference = registration.tokenReference,
                    requestorId = registration.requestorId,
                    requestorLabel = registration.requestorLabel,
                    scheme = registration.scheme.name,
                    status = registration.status.name,
                    expiry = registration.expiry,
                    occurredAt = now,
                )
                val saved = registrations.save(
                    registration,
                    outboxMessage(registration.id, CardTokenProvisioned.EVENT_TYPE, event),
                    command.idempotencyKey,
                )
                metrics.tokenProvisioned(answer.scheme.name, null)
                TokenOutcome.Provisioned(saved)
            }

            is SchemeResult.Unanswered -> {
                metrics.tokenProvisioned(answer.scheme.name, refusalFor(answer.failure).name)
                log.infof(
                    "token provisioning refused for card %s: %s (%s) — %s",
                    command.cardId,
                    answer.failure,
                    answer.scheme,
                    answer.detail,
                )
                TokenOutcome.Refused(refusalFor(answer.failure), answer.detail)
            }
        }
    }

    override suspend fun changeStatus(command: ChangeTokenStatusCommand): TokenOutcome {
        val existing = registrations.findByTokenReference(command.tokenReference)
            ?: return TokenOutcome.Refused(TokenRefusal.TOKEN_NOT_FOUND, "no token ${command.tokenReference}")

        // Checked here as well as in the adapter. The rule belongs to the aggregate: a caller must
        // get the same refusal whichever binding is wired, and a rule that lives only in the
        // simulator is a rule this service cannot state about itself.
        if (existing.terminal) {
            metrics.tokenStatusChanged(
                existing.scheme.name,
                command.status.name,
                TokenRefusal.TOKEN_TERMINAL.name,
            )
            return TokenOutcome.Refused(
                TokenRefusal.TOKEN_TERMINAL,
                "token ${command.tokenReference} is DELETED, which is terminal",
            )
        }

        return when (val answer = tokenisation.changeStatus(command.tokenReference, command.status)) {
            is SchemeResult.Answered -> {
                val now = Instant.now(clock)
                val updated = existing.copy(
                    status = answer.value.status,
                    expiry = answer.value.expiry ?: existing.expiry,
                    updatedAt = now,
                )
                val event = CardTokenStatusChanged(
                    registrationId = updated.id,
                    cardId = updated.cardId,
                    tokenReference = updated.tokenReference,
                    previousStatus = existing.status.name,
                    status = updated.status.name,
                    scheme = updated.scheme.name,
                    occurredAt = now,
                )
                val saved = registrations.save(
                    updated,
                    outboxMessage(updated.id, CardTokenStatusChanged.EVENT_TYPE, event),
                    // The status change reuses the registration's key: the UNIQUE index protects the
                    // ROW's identity, and this row already exists. A second key here would be a
                    // second identity for one registration.
                    idempotencyKeyOf(updated),
                )
                metrics.tokenStatusChanged(updated.scheme.name, updated.status.name, null)
                TokenOutcome.Changed(saved)
            }

            is SchemeResult.Unanswered -> {
                metrics.tokenStatusChanged(
                    answer.scheme.name,
                    command.status.name,
                    refusalFor(answer.failure).name,
                )
                TokenOutcome.Refused(refusalFor(answer.failure), answer.detail)
            }
        }
    }

    override suspend fun listForCard(cardId: UUID): TokenRegistrations {
        val mirror = registrations.findByCardId(cardId)
        return when (val answer = tokenisation.listTokens(cardId.toString())) {
            is SchemeResult.Answered -> {
                metrics.tokenListServed(TokenReadSource.NETWORK.name)
                TokenRegistrations(reconcile(cardId, answer.scheme, answer.value, mirror), TokenReadSource.NETWORK)
            }

            is SchemeResult.Unanswered -> {
                metrics.tokenListServed(TokenReadSource.LOCAL_MIRROR.name)
                TokenRegistrations(
                    mirror,
                    TokenReadSource.LOCAL_MIRROR,
                    "${answer.failure} from ${answer.scheme}: ${answer.detail ?: "no detail"}",
                )
            }
        }
    }

    /**
     * The network's list, described with the mirror's metadata.
     *
     * The network knows the token and its state; only this bank knows which requestor label an
     * operator gave it and when it was first seen here. A token the network returns that has no
     * mirror row is still returned — it exists, and hiding it would make the screen disagree with
     * the network — carrying the placeholder label and the network's own timing.
     *
     * This does NOT write the mirror back. A read path that silently repairs its own store makes
     * every drift invisible, and drift between the vault and the mirror is a thing an operator has
     * to be able to see.
     */
    private fun reconcile(
        cardId: UUID,
        scheme: CardScheme,
        live: List<NetworkToken>,
        mirror: List<CardTokenRegistration>,
    ): List<CardTokenRegistration> {
        val byReference = mirror.associateBy { it.tokenReference }
        return live.map { token ->
            val known = byReference[token.tokenReference]
            known?.copy(status = token.status, expiry = token.expiry ?: known.expiry)
                ?: CardTokenRegistration(
                    id = Ids.newId(),
                    cardId = cardId,
                    tokenReference = token.tokenReference,
                    requestorId = token.requestorId ?: UNKNOWN_REQUESTOR,
                    requestorLabel = UNMIRRORED_LABEL,
                    last4 = token.last4,
                    status = token.status,
                    // The scheme comes from the ANSWER, not from the token: `NetworkToken` carries
                    // no scheme of its own, because which network replied is a property of the call.
                    scheme = scheme,
                    expiry = token.expiry,
                    provisionedAt = Instant.now(clock),
                    updatedAt = Instant.now(clock),
                )
        }
    }

    private fun idempotencyKeyOf(registration: CardTokenRegistration) = "token:${registration.tokenReference}"

    private fun refusalFor(failure: SchemeFailure): TokenRefusal = when (failure) {
        SchemeFailure.NOT_BOUND, SchemeFailure.UNAVAILABLE, SchemeFailure.UNAUTHENTICATED ->
            TokenRefusal.SCHEME_UNAVAILABLE
        SchemeFailure.NOT_FOUND -> TokenRefusal.TOKEN_NOT_FOUND
        SchemeFailure.MALFORMED -> TokenRefusal.SCHEME_REFUSED
    }

    /** `createdAt` from the injected clock, never the default — see `CardProcessingService`. */
    private fun outboxMessage(aggregateId: UUID, eventType: String, event: CardLifecycleEvent): OutboxMessage =
        OutboxMessage(
            aggregateId = aggregateId,
            eventType = eventType,
            payload = mapper.writeValueAsString(event),
            createdAt = Instant.now(clock),
        )

    private companion object {
        const val UNKNOWN_REQUESTOR = "UNKNOWN"
        const val UNMIRRORED_LABEL = "not recorded by this bank"
    }
}
