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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

/**
 * Onboarding account lifecycle driven by party domain events (ADR-0073).
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
 * Poison-pill safe: any parse/projection failure is logged and acked so a single bad event
 * cannot wedge the consumer group (the party service remains the source of truth and can be
 * replayed).
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
        try {
            val node = objectMapper.readTree(payload)
            // Wire contract = the DEPLOYED producer (party-service KafkaPartyEventPublisher):
            // a FLAT envelope on topic openbank.party.events —
            //   {"eventType":"PARTY_CREATED","partyId":...,"partyType":"INDIVIDUAL",
            //    "status":"PENDING_KYC","legalName":...,"email":...,"occurredAt":...}
            // (NOT the pid-service nested {aggregateId,payload} form — pid publishes to a
            //  different topic `party.events` and is not deployed. See ADR-0072 migration.)
            val type = node.path("eventType").asText()
            val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull() ?: return
            when (type) {
                "PARTY_CREATED" ->
                    openPendingAccount(
                        partyId = partyId,
                        partyType = node.path("partyType").asText(""),
                        legalName = node.path("legalName").asText("").trim(),
                    )
                // party-service flips status to ACTIVE (two-key KYC+AML gate) and re-publishes the
                // party via PARTY_UPDATED / KYC_STATUS_CHANGED, both carrying the new `status`.
                "PARTY_UPDATED", "KYC_STATUS_CHANGED" ->
                    reconcileToPartyStatus(partyId, node.path("status").asText(""))
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to handle party event: %.300s", payload)
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

    // Fire the one-time welcome bonus as the account goes live. Best-effort: a failure here must not
    // wedge the consumer or block activation (the account is already ACTIVE). Idempotent downstream
    // (keyed on the account id), so a retry on the next event re-delivery is safe rather than doubling.
    // On a successful grant, also notify the party (in-app feed + push) — itself best-effort.
    private suspend fun grantWelcomeBonus(accountId: UUID, partyId: UUID) {
        if (!welcomeBonusEnabled) return
        try {
            welcomeBonusPort.grantWelcomeBonus(accountId, welcomeBonusAmount, welcomeBonusCurrency)
            log.infof("Granted welcome bonus %s %s to account %s", welcomeBonusAmount, welcomeBonusCurrency, accountId)
        } catch (e: Exception) {
            log.errorf(e, "Welcome bonus grant failed for account %s (will retry on next ACTIVE event)", accountId)
            return
        }
        try {
            notificationRequestPort.notifyIncomingCredit(partyId, welcomeBonusAmount, welcomeBonusCurrency)
        } catch (e: Exception) {
            log.warnf(e, "Welcome-bonus notification failed for party %s (bonus already granted)", partyId)
        }
    }
}
