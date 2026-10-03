// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardprocessing.application.port.`in`.CardTokenUseCase
import com.openbank.cardprocessing.application.port.`in`.ChangeTokenStatusCommand
import com.openbank.cardprocessing.application.port.`in`.ProvisionTokenCommand
import com.openbank.cardprocessing.application.port.out.CardIssuerUnavailableException
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardTokenRegistrationRepository
import com.openbank.cardprocessing.application.port.out.IdempotencyClaim
import com.openbank.cardprocessing.application.port.out.LifecycleIdempotencyPort
import com.openbank.cardprocessing.application.port.out.LifecycleOperation
import com.openbank.cardprocessing.application.port.out.Reservation
import com.openbank.cardprocessing.application.port.out.UNATTRIBUTED_SCHEME
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
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

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
@Suppress(
    // Named private steps of one provisioning flow (pre-network refusal, network call, fingerprint,
    // reconcile); splitting the class would put one flow across two files for a metric.
    "TooManyFunctions",
)
class CardTokenService(
    private val tokenisation: TokenisationPort,
    private val registrations: CardTokenRegistrationRepository,
    private val idempotency: LifecycleIdempotencyPort,
    private val cards: CardLookupPort,
    private val metrics: CardLifecycleMetricsPort,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : CardTokenUseCase {

    private val log = Logger.getLogger(CardTokenService::class.java)

    override suspend fun provision(command: ProvisionTokenCommand): TokenOutcome {
        val claim = IdempotencyClaim(LifecycleOperation.TOKEN_PROVISION, command.idempotencyKey)
        when (val reservation = idempotency.reserve(claim.operation, claim.key, fingerprintOf(command))) {
            is Reservation.Completed -> {
                // The replay answers from the winner's row — even if the card has been blocked since:
                // the token already exists, and this request is the same request, not a new one.
                val existing = registrations.findById(reservation.resultId)
                    ?: error("idempotency reservation points at missing token registration ${reservation.resultId}")
                return TokenOutcome.Provisioned(existing)
            }
            Reservation.InProgress -> throw IdempotencyRequestInProgressException()
            Reservation.Mismatch -> throw IdempotencyKeyReusedException()
            Reservation.Claimed -> Unit
        }

        // From here this request HOLDS the key. Every exit before the network answers releases it;
        // an exit after the network was called and before the row committed leaves it PENDING, because
        // the network may have minted a token and a retry must not mint a second.
        var networkCalled = false
        try {
            refuseBeforeNetwork(command)?.let {
                idempotency.release(claim)
                return it
            }
            networkCalled = true
            val outcome = callNetwork(command, claim)
            if (outcome is TokenOutcome.Refused) idempotency.release(claim)
            return outcome
        } catch (e: CancellationException) {
            if (!networkCalled) withContext(NonCancellable) { idempotency.release(claim) }
            throw e
        } catch (
            // Anything at all — a reservation left PENDING by a failure before the network was asked
            // would block this key forever for no reason.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (!networkCalled) {
                idempotency.release(claim)
            } else {
                log.errorf(
                    e,
                    "token provisioning for card %s failed after the network was called; idempotency key " +
                        "left PENDING so a retry cannot mint a second token — reconcile with the scheme",
                    command.cardId,
                )
            }
            throw e
        }
    }

    /**
     * The checks that need no network: the card must exist, card-issuance must be reachable to say so,
     * and the card must be ACTIVE. Null when provisioning may proceed.
     */
    private suspend fun refuseBeforeNetwork(command: ProvisionTokenCommand): TokenOutcome.Refused? {
        val card = try {
            cards.lookup(command.cardId)
        } catch (e: CardIssuerUnavailableException) {
            // Fails CLOSED, like authorisation: no token is minted against a card nobody could
            // confirm. A 503, not a 404 — the card may well exist.
            log.warnf(e, "token provisioning for card %s refused: card-issuance unreachable", command.cardId)
            return refusedBeforeNetwork(TokenRefusal.ISSUER_UNAVAILABLE, "card-issuance could not be reached")
        } ?: return refusedBeforeNetwork(TokenRefusal.CARD_NOT_FOUND, "no card ${command.cardId}")

        if (!card.active) {
            return refusedBeforeNetwork(
                TokenRefusal.CARD_NOT_ACTIVE,
                "card ${command.cardId} is ${card.status ?: "of unknown state"}; only an ACTIVE card may be tokenised",
            )
        }
        return null
    }

    private fun refusedBeforeNetwork(reason: TokenRefusal, detail: String): TokenOutcome.Refused {
        metrics.tokenProvisioned(UNATTRIBUTED_SCHEME, reason.name)
        return TokenOutcome.Refused(reason, detail)
    }

    private suspend fun callNetwork(command: ProvisionTokenCommand, claim: IdempotencyClaim): TokenOutcome {
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
                    claim,
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

    /** The request an `Idempotency-Key` is bound to: same key, different card or requestor is reuse. */
    private fun fingerprintOf(command: ProvisionTokenCommand): String = RequestFingerprint.of(
        "POST",
        "/api/v1/card-tokens",
        listOf(command.cardId, command.requestorId, command.requestorLabel).joinToString("\n"),
    )

    /**
     * Suspend, resume or delete a token — idempotently.
     *
     * A status change reaches the network, so a retried request must not reach it twice: a DELETE
     * replayed after a crash is harmless at most schemes, but a SUSPEND replayed after a later RESUME
     * would silently re-suspend a credential the customer was just given back. The caller's
     * `Idempotency-Key` is reserved with the same mechanism as [provision]; the reservation completes
     * in the transaction that writes the new status and its event.
     */
    override suspend fun changeStatus(command: ChangeTokenStatusCommand): TokenOutcome {
        val claim = IdempotencyClaim(LifecycleOperation.TOKEN_STATUS_CHANGE, command.idempotencyKey)
        when (val reservation = idempotency.reserve(claim.operation, claim.key, fingerprintOf(command))) {
            is Reservation.Completed -> {
                val existing = registrations.findById(reservation.resultId)
                    ?: error("idempotency reservation points at missing token registration ${reservation.resultId}")
                return TokenOutcome.Changed(existing)
            }
            Reservation.InProgress -> throw IdempotencyRequestInProgressException()
            Reservation.Mismatch -> throw IdempotencyKeyReusedException()
            Reservation.Claimed -> Unit
        }
        val network = NetworkCall()
        try {
            val outcome = changeStatusClaimed(command, claim, network)
            if (outcome is TokenOutcome.Refused) idempotency.release(claim)
            return outcome
        } catch (e: CancellationException) {
            if (!network.called) withContext(NonCancellable) { idempotency.release(claim) }
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (!network.called) {
                idempotency.release(claim)
            } else {
                log.errorf(
                    e,
                    "token status change for %s failed after the network was called; idempotency key left " +
                        "PENDING so a retry cannot repeat the network call — reconcile with the scheme",
                    command.tokenReference,
                )
            }
            throw e
        }
    }

    /** Set once the network has been asked; after that a failure must leave the reservation PENDING. */
    private class NetworkCall {
        var called = false
    }

    /** Same key, different token or different target status is reuse. */
    private fun fingerprintOf(command: ChangeTokenStatusCommand): String = RequestFingerprint.of(
        "POST",
        "/api/v1/card-tokens/${command.tokenReference}/status",
        command.status.name,
    )

    private suspend fun changeStatusClaimed(
        command: ChangeTokenStatusCommand,
        claim: IdempotencyClaim,
        network: NetworkCall,
    ): TokenOutcome {
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

        network.called = true
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
                    // Ignored for an existing row (the key is written once, at insert); passed only
                    // because the signature is shared with the insert path.
                    idempotencyKeyOf(updated),
                    claim,
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
     * The network's list joined with the mirror, losing neither side.
     *
     * - A token the network returns that the mirror lacks is ADOPTED: written to the mirror under the
     *   network's token reference, so it has one stable id from the first read on and its existence is
     *   on record even after the network stops returning it. Inserting is safe on a read path because
     *   it only adds what the network has just confirmed; it never rewrites an existing row, and the
     *   drift stays visible — an adopted row carries the [UNMIRRORED_LABEL] and no requestor this bank
     *   recorded.
     * - A token the mirror holds that the network did NOT return is kept and flagged
     *   [absentAtNetwork][CardTokenRegistration.absentAtNetwork]. Dropping it would make a deleted or
     *   lost token vanish from the one screen that should show the disagreement.
     *
     * The live status is overlaid on the response only; the stored status changes through
     * [changeStatus] and its event, never silently on a read.
     */
    private suspend fun reconcile(
        cardId: UUID,
        scheme: CardScheme,
        live: List<NetworkToken>,
        mirror: List<CardTokenRegistration>,
    ): List<CardTokenRegistration> {
        val byReference = mirror.associateBy { it.tokenReference }
        val unknown = live.filter { it.tokenReference !in byReference }
        val adopted = if (unknown.isEmpty()) {
            emptyMap()
        } else {
            val now = Instant.now(clock)
            registrations.adoptNetworkSeen(
                unknown.map { token ->
                    CardTokenRegistration(
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
                        provisionedAt = now,
                        updatedAt = now,
                    )
                },
            ).associateBy { it.tokenReference }
        }
        val liveRows = live.mapNotNull { token ->
            val stored = byReference[token.tokenReference] ?: adopted[token.tokenReference]
            stored?.copy(status = token.status, expiry = token.expiry ?: stored.expiry)
        }
        val liveReferences = live.mapTo(HashSet()) { it.tokenReference }
        val absent = mirror.filter { it.tokenReference !in liveReferences }.map { it.copy(absentAtNetwork = true) }
        if (absent.isNotEmpty()) {
            log.infof("%d mirrored token(s) for card %s were not returned by %s", absent.size, cardId, scheme)
        }
        return liveRows + absent
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
