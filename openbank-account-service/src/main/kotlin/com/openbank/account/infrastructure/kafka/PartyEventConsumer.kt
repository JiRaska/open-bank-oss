// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.NotificationRequestPort
import com.openbank.account.application.port.out.WelcomeBonusPort
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.libs.domain.money.CurrencyCode
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.delay
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

/** The subset of the party-event envelope this consumer projects. */
private data class PartyEvent(
    val type: String,
    val partyId: UUID,
    val partyType: String,
    val legalName: String,
    val status: String,
)

/**
 * Onboarding account lifecycle driven by party domain events (ADR-0267).
 *
 * - PARTY_CREATED (INDIVIDUAL) → open a PENDING_ACTIVATION multi-currency CURRENT account
 *   (one IBAN + primary CZK pocket) plus a SAVINGS account, so a fresh customer can try
 *   pocket moves / deposits out of the box. Both are inert until activated —
 *   `canDebit`/`canCredit` both require ACTIVE — so no money can move before KYC + AML clear.
 * - party becomes ACTIVE (KYC + AML both passed, decided by party-service's two-key gate)
 *   → activate the party's pending accounts; the welcome bonus lands on the CURRENT
 *   account only (never once per account).
 * - party SUSPENDED → freeze any active account (defence in depth).
 *
 * Idempotent: one onboarding account per party AND type; re-delivered events are no-ops.
 *
 * Failure handling distinguishes two cases that an earlier catch-all conflated — a conflation
 * that turned a transient outage into permanent, silent data loss (a whole cohort of customers
 * had their PARTY_CREATED acked-and-dropped and never got an account, 2026-06-24..2026-07-17):
 *  - **Poison pill** — unparseable, or missing the partyId. It can never succeed, so it is logged
 *    and acked (returning): retrying or dead-lettering it would only wedge or fill the DLQ.
 *  - **Transient projection failure** — a well-formed event we simply could not project yet
 *    because a dependency was momentarily down (a scaled-to-zero cold start, a DB blip). A short
 *    bounded retry absorbs the blip; if it still fails the exception ESCAPES, and SmallRye routes
 *    the record to the dead-letter topic (`failure-strategy: dead-letter-queue`, application.yaml)
 *    — parked for replay, never destroyed, and the consumer keeps moving. The party service
 *    remains the source of truth and the DLQ record is recoverable.
 */
@ApplicationScoped
// Constructor width comes from @ConfigProperty injection (the bonus feature alone is a
// config triplet) — a config holder class would only move the noise, not remove it.
@Suppress("LongParameterList")
class PartyEventConsumer(
    private val accountUseCase: AccountUseCase,
    private val accountRepository: AccountRepository,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(
        name = "openbank.account.onboarding.default-product-id",
        defaultValue = "00000000-0000-0000-0000-0000000000c2",
    )
    private val defaultProductId: String,
    @ConfigProperty(
        name = "openbank.account.onboarding.savings-product-id",
        defaultValue = "00000000-0000-0000-0000-0000000000c3",
    )
    private val savingsProductId: String,
    @ConfigProperty(name = "openbank.account.onboarding.default-currency", defaultValue = "CZK")
    private val defaultCurrency: String,
    @ConfigProperty(
        name = "openbank.account.onboarding.system-actor-id",
        defaultValue = "00000000-0000-0000-0000-0000000005ec",
    )
    private val systemActorId: String,
    private val welcomeBonusPort: WelcomeBonusPort,
    // Sandbox onboarding incentive: a one-time incoming credit granted as the account activates.
    // Default OFF — must never run in production (it conjures money from the bank's clearing account).
    @ConfigProperty(name = "openbank.welcome-bonus.enabled", defaultValue = "false")
    private val welcomeBonusEnabled: Boolean,
    @ConfigProperty(name = "openbank.welcome-bonus.amount", defaultValue = "100000.00")
    private val welcomeBonusAmount: BigDecimal,
    @ConfigProperty(name = "openbank.welcome-bonus.currency", defaultValue = "CZK")
    private val welcomeBonusCurrency: String,
    private val notificationRequestPort: NotificationRequestPort,
) {
    private val log = Logger.getLogger(PartyEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val event = parseEnvelope(payload)
        if (event == null) {
            // Poison pill: unparseable, or no usable partyId. It can never succeed — acking it (by
            // returning) is correct. Do NOT let it reach the retry/DLQ path: retrying is pointless
            // and dead-lettering it just fills the DLQ with events nothing can ever process.
            log.warnf("Dropping unprocessable party event (poison pill): %.300s", payload)
            return
        }
        // A well-formed event; a projection failure here is transient (dependency momentarily
        // down), so it must NOT be swallowed. A short bounded retry absorbs a blip (e.g. a
        // scaled-to-zero dependency's cold start); if it still fails the exception escapes and
        // SmallRye dead-letters the record for replay (failure-strategy in application.yaml).
        withBoundedRetry(event) { dispatch(event) }
    }

    /**
     * Wire contract = the DEPLOYED producer (party-service KafkaPartyEventPublisher): a FLAT
     * envelope on topic openbank.party.events —
     *   {"eventType":"PARTY_CREATED","partyId":...,"partyType":"INDIVIDUAL",
     *    "status":"PENDING_KYC","legalName":...,"email":...,"occurredAt":...}
     * (NOT the pid-service nested {aggregateId,payload} form — pid publishes to a different topic
     *  `party.events` and is not deployed. See ADR-0072 migration.)
     *
     * Returns null only for a genuine poison pill — malformed JSON, or a missing/non-UUID partyId.
     * An unknown eventType is NOT poison: it parses fine and [dispatch] simply no-ops on it.
     */
    private fun parseEnvelope(payload: String): PartyEvent? {
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return null
        val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull() ?: return null
        return PartyEvent(
            type = node.path("eventType").asText(""),
            partyId = partyId,
            partyType = node.path("partyType").asText(""),
            legalName = node.path("legalName").asText("").trim(),
            status = node.path("status").asText(""),
        )
    }

    private suspend fun dispatch(event: PartyEvent) {
        when (event.type) {
            "PARTY_CREATED" -> openPendingAccount(event.partyId, event.partyType, event.legalName)
            // party-service flips status to ACTIVE (two-key KYC+AML gate) and re-publishes the
            // party via PARTY_UPDATED / KYC_STATUS_CHANGED, both carrying the new `status`.
            "PARTY_UPDATED", "KYC_STATUS_CHANGED" -> reconcileToPartyStatus(event.partyId, event.status)
            "PARTY_ERASED" -> handleErased(event.partyId)
            else -> Unit // a type we don't project — nothing to do, ack.
        }
    }

    /**
     * Retries [block] a bounded number of times with linear backoff, then rethrows so the message
     * is nacked to the DLQ. Deliberately retries on ANY exception: from here every failure is a
     * transient projection failure (poison pills were filtered out before this is called), and the
     * projections are idempotent (keyed on partyId + account type), so a retry re-runs safely.
     * The per-attempt log carries the exception type + message, which the service's JSON log format
     * (`%s`, no `%e`) would otherwise drop — so a failure is diagnosable without the stack trace.
     */
    private suspend fun withBoundedRetry(event: PartyEvent, block: suspend () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt >= MAX_PROJECTION_ATTEMPTS) {
                    log.errorf(
                        e,
                        "party event %s/%s failed after %d attempts (%s: %s) — dead-lettering",
                        event.type,
                        event.partyId,
                        attempt,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    throw e
                }
                log.warnf(
                    "party event %s/%s projection attempt %d/%d failed (%s: %s) — retrying in %dms",
                    event.type,
                    event.partyId,
                    attempt,
                    MAX_PROJECTION_ATTEMPTS,
                    e.javaClass.simpleName,
                    e.message,
                    RETRY_BACKOFF_MS * attempt,
                )
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    private suspend fun openPendingAccount(partyId: UUID, partyType: String, legalName: String) {
        // Retail onboarding only; legal entities / sole traders are opened by an operator.
        // party-service PartyType enum value for a natural person is INDIVIDUAL.
        if (partyType != "INDIVIDUAL") return
        // Idempotent per account TYPE: a re-delivered event (or a party predating the savings
        // rollout) only opens whatever is still missing.
        val existingTypes = accountRepository.findByPartyId(partyId, 50, null).map { it.accountType }.toSet()
        if (AccountType.CURRENT !in existingTypes) {
            accountUseCase.openAccount(
                OpenAccountCommand(
                    // Key kept from the single-account era so historic retries stay no-ops.
                    idempotencyKey = "onboarding-account-$partyId",
                    partyId = partyId,
                    productId = UUID.fromString(defaultProductId),
                    accountType = AccountType.CURRENT,
                    currency = CurrencyCode.of(defaultCurrency),
                    requestedBy = UUID.fromString(systemActorId),
                    legalName = legalName,
                    initialStatus = AccountStatus.PENDING_ACTIVATION,
                ),
            )
            log.infof("Opened PENDING_ACTIVATION onboarding CURRENT account for party %s", partyId)
        }
        if (AccountType.SAVINGS !in existingTypes) {
            accountUseCase.openAccount(
                OpenAccountCommand(
                    idempotencyKey = "onboarding-savings-$partyId",
                    partyId = partyId,
                    productId = UUID.fromString(savingsProductId),
                    accountType = AccountType.SAVINGS,
                    currency = CurrencyCode.of(defaultCurrency),
                    requestedBy = UUID.fromString(systemActorId),
                    legalName = legalName,
                    initialStatus = AccountStatus.PENDING_ACTIVATION,
                ),
            )
            log.infof("Opened PENDING_ACTIVATION onboarding SAVINGS account for party %s", partyId)
        }
    }

    private suspend fun reconcileToPartyStatus(partyId: UUID, status: String) {
        when (status) {
            "ACTIVE" -> accountRepository.findByPartyId(partyId, 50, null)
                .filter { it.status == AccountStatus.PENDING_ACTIVATION }
                .forEach {
                    accountUseCase.activateAccount(it.id)
                    log.infof("Activated onboarding account %s for party %s", it.id, partyId)
                    // The incentive is one credit per CUSTOMER, not per account — the savings
                    // account activates dry and gets funded by the customer's own transfers.
                    if (it.accountType == AccountType.CURRENT) grantWelcomeBonus(it.id, partyId)
                }
        }
    }

    // GDPR Art. 17 right to erasure: null out the stored legalName for every account of the
    // erased party. A transient DB failure here deliberately propagates (to retry then DLQ, via
    // consume's withBoundedRetry): a swallowed failure would leave the erasure silently
    // incomplete, a compliance breach. anonymizeByPartyId is idempotent (nulling an already-null
    // name is a no-op), so a retry or a replay re-runs safely.
    private suspend fun handleErased(partyId: UUID) {
        val count = accountRepository.anonymizeByPartyId(partyId)
        log.infof("GDPR Art. 17: anonymised legalName for erased party %s (%d account(s))", partyId, count)
    }

    // Fire the one-time welcome bonus as the account goes live. Idempotent downstream (keyed on the
    // account id), so a retry or a redelivery cannot double-credit.
    private suspend fun grantWelcomeBonus(accountId: UUID, partyId: UUID) {
        if (!welcomeBonusEnabled) return
        // The old code caught this and logged "will retry on next ACTIVE event". There IS no next
        // ACTIVE event — a party activates once — so the customer simply never got the money, and
        // the only trace was an ERROR line (#5698). It now propagates.
        //
        // Deliberately NOT wrapped in EventRetry here: consume() already runs this whole path
        // through withBoundedRetry, and nesting the two multiplies the attempts (4 x 3 = 12 calls
        // to the payment path for one event, which the test caught). One retry loop per message.
        welcomeBonusPort.grantWelcomeBonus(accountId, welcomeBonusAmount, welcomeBonusCurrency)
        log.infof("Granted welcome bonus %s %s to account %s", welcomeBonusAmount, welcomeBonusCurrency, accountId)
        // best-effort: the money is already booked and the event is complete without this. A failed
        // notification costs the customer a push, not their balance — the one shape of failure a
        // handler may swallow, and it is stated here rather than left to a bare catch.
        try {
            notificationRequestPort.notifyIncomingCredit(partyId, welcomeBonusAmount, welcomeBonusCurrency)
        } catch (e: Exception) {
            log.warnf(e, "Welcome-bonus notification failed for party %s (bonus already granted)", partyId)
        }
    }

    private companion object {
        // Small and bounded: enough to ride out a dependency cold start / brief blip (a few
        // seconds total), not so many that a genuinely-down dependency delays dead-lettering for
        // long. Linear backoff: RETRY_BACKOFF_MS, then 2x, then 3x (~3s total across 3 attempts).
        const val MAX_PROJECTION_ATTEMPTS = 4
        const val RETRY_BACKOFF_MS = 500L
    }
}
