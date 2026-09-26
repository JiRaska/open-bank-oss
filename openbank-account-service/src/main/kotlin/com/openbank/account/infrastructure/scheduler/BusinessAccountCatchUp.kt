// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.onboarding.BusinessOnboardingAccount
import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.PartyDirectoryPort
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID

/**
 * Catch-up for business parties that are already ACTIVE but have no business current account.
 *
 * The event path (party-ACTIVE on `party-events-in`) only fires on the transition. A business party
 * that became ACTIVE before this service could react — created before business accounts were
 * opened, or activated while this code was not yet deployed — gets no second event, so it would
 * wait for an operator forever. This job closes that gap and makes the outcome independent of
 * deploy order: shortly after start and then on every interval it pages party-service's ACTIVE
 * parties and, for each COMPANY / SOLE_TRADER, opens the business current account through
 * [BusinessOnboardingAccount] (same product, currency and idempotency key as the event path, so a
 * race or a replay still yields one account) and activates any `PENDING_ACTIVATION` business
 * CURRENT account it holds, as the ACTIVE event would have.
 *
 * Runs only when BOTH `openbank.account.onboarding.open-business-accounts` and
 * `openbank.account.onboarding.business-catch-up.enabled` are on (both default false; sandbox on).
 * Disabled, it calls nothing and registers no liveness gauge. `suspend`, never `runBlocking`
 * (#2148). One party's failure is logged (party id only) and retried on the next tick.
 */
@Startup
@ApplicationScoped
@Suppress("LongParameterList")
class BusinessAccountCatchUp(
    private val parties: PartyDirectoryPort,
    private val accountRepository: AccountRepository,
    private val accountUseCase: AccountUseCase,
    private val registry: MeterRegistry,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.account.onboarding.open-business-accounts", defaultValue = "false")
    private val openBusinessAccounts: Boolean,
    @ConfigProperty(name = "openbank.account.onboarding.business-catch-up.enabled", defaultValue = "false")
    private val catchUpEnabled: Boolean,
    @ConfigProperty(name = "openbank.account.onboarding.business-catch-up.page-size", defaultValue = "100")
    private val pageSize: Int,
    @ConfigProperty(name = "openbank.account.onboarding.business-catch-up.max-pages", defaultValue = "20")
    private val maxPages: Int,
    @ConfigProperty(
        name = "openbank.account.onboarding.business-product-id",
        defaultValue = "d4275d2a-1343-3052-a6c0-8a99149b6c62",
    )
    private val businessProductId: String,
    @ConfigProperty(name = "openbank.account.onboarding.business-currency", defaultValue = "EUR")
    private val businessCurrency: String,
    @ConfigProperty(
        name = "openbank.account.onboarding.system-actor-id",
        defaultValue = "00000000-0000-0000-0000-0000000005ec",
    )
    private val systemActorId: String,
) {
    private val log = Logger.getLogger(BusinessAccountCatchUp::class.java)
    private lateinit var opened: Counter
    private lateinit var failed: Counter
    private var liveness: WorkflowLivenessRecorder? = null

    private val enabled get() = openBusinessAccounts && catchUpEnabled

    private val opener by lazy {
        BusinessOnboardingAccount(
            accountRepository,
            accountUseCase,
            UUID.fromString(businessProductId),
            businessCurrency,
            UUID.fromString(systemActorId),
        )
    }

    @PostConstruct
    fun register() {
        opened = Counter.builder("openbank.account.onboarding.business_accounts_opened_by_catch_up")
            .description("Business current accounts opened by the catch-up for an ACTIVE party that had none")
            .tag("service", "account")
            .register(registry)
        failed = Counter.builder("openbank.account.onboarding.business_catch_up_failures")
            .description("ACTIVE business parties the catch-up could not handle on a tick")
            .tag("service", "account")
            .register(registry)
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "{openbank.account.onboarding.business-catch-up.interval:15m}",
        delayed = "{openbank.account.onboarding.business-catch-up.initial-delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "business-account-catch-up",
    )
    suspend fun tick() {
        if (!enabled) return
        catchUpOnce()
        liveness?.recordSuccess()
    }

    /** One full pass; returns how many accounts it opened. party-service unreachable throws. */
    suspend fun catchUpOnce(): Int {
        var openedNow = 0
        var page = 0
        while (page < maxPages) {
            val batch = parties.listActive(page, pageSize)
            batch.items
                .filter { it.status == ACTIVE && it.partyType in BusinessOnboardingAccount.BUSINESS_PARTY_TYPES }
                .forEach { if (handle(it.partyId, it.partyType, it.legalName)) openedNow++ }
            if (!batch.hasMore) break
            page++
        }
        if (page >= maxPages) log.warnf("[business-catch-up] stopped at max-pages=%d", maxPages)
        return openedNow
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun handle(partyId: UUID, partyType: String, legalName: String): Boolean = try {
        val openedHere = opener.openIfMissing(partyId, legalName)
        if (openedHere) {
            opened.increment()
            log.infof("[business-catch-up] opened business CURRENT account for ACTIVE %s party %s", partyType, partyId)
        }
        val product = UUID.fromString(businessProductId)
        accountRepository.findByPartyId(partyId, BusinessOnboardingAccount.PAGE, null)
            .filter {
                it.status == AccountStatus.PENDING_ACTIVATION &&
                    it.accountType == AccountType.CURRENT &&
                    it.productId == product
            }
            .forEach {
                accountUseCase.activateAccount(it.id)
                log.infof("[business-catch-up] activated business account %s for party %s", it.id, partyId)
            }
        openedHere
    } catch (e: Exception) {
        // observed-by: the business_catch_up_failures counter. Nothing is acked away: the input is
        // re-read from party-service every tick, so the same party is retried on the next one.
        failed.increment()
        log.warnf(e, "[business-catch-up] could not handle party %s; retried next tick", partyId)
        false
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val WORKFLOW_NAME = "account-business-catch-up"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
