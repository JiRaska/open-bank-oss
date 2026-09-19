// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.scheduler

import com.openbank.aml.application.onboarding.OnboardingScreening
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.application.port.out.PartyDirectoryPort
import com.openbank.aml.application.port.out.PartySummary
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Self-heals parties that reached KYC `APPROVED` without ever getting an onboarding AML case.
 *
 * ### The defect class
 *
 * The onboarding case is opened by the PARTY_CREATED consumer. A party created before this service
 * screened its type (business types were added later), or whose PARTY_CREATED aged out of the topic
 * before it was consumed, has no case — so it never clears the AML key of the two-key activation
 * gate (ADR-0267 §2) and waits in `PENDING_KYC` forever. Until now the only repair was an operator
 * hand-calling APIs.
 *
 * ### What a tick does
 *
 * Pages through party-service's `PENDING_KYC` parties and, for each one that is KYC `APPROVED`, of a
 * screened type ([OnboardingScreening.SCREENED_PARTY_TYPES]) and has NO case under
 * `<partyId>:CUSTOMER_ONBOARDING`, opens one through [OnboardingScreening.open] — the same code
 * path the consumer uses, so the sandbox auto-clear or the analyst queue decides it exactly as it
 * would have at creation. A party that already has a case (open, cleared or blocked) is never
 * touched: an open case is an analyst's, not this job's. Opening is idempotent on the case key, so
 * two ticks, two replicas or a racing consumer still produce one case.
 *
 * ### Safety
 *
 * `openbank.aml.onboarding-reconcile.enabled` defaults to **false** in code and in
 * `application.yaml`; the sandbox turns it on through gitops. Disabled, the job reads nothing and
 * registers no liveness gauge (so a deliberately-off job cannot page anyone). The walk is bounded by
 * `max-pages`. Logs carry the party id only — no names, e-mail or other personal data are even
 * deserialised from party-service's response.
 *
 * `suspend`, never `runBlocking` (#2148): a plain `@Scheduled` method has no Vert.x context and the
 * first reactive Panache call aborts the tick.
 */
@Startup
@ApplicationScoped
class OnboardingScreeningReconciler {

    @Inject
    lateinit var parties: PartyDirectoryPort

    @Inject
    lateinit var caseRepository: AmlCaseRepository

    @Inject
    lateinit var amlUseCase: AmlCaseUseCase

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @ConfigProperty(name = "openbank.aml.onboarding-reconcile.enabled", defaultValue = "false")
    var enabled: Boolean = false

    @ConfigProperty(name = "openbank.aml.onboarding-reconcile.page-size", defaultValue = "100")
    var pageSize: Int = DEFAULT_PAGE_SIZE

    @ConfigProperty(name = "openbank.aml.onboarding-reconcile.max-pages", defaultValue = "20")
    var maxPages: Int = DEFAULT_MAX_PAGES

    @ConfigProperty(name = "openbank.aml.auto-clear", defaultValue = "false")
    var autoClear: Boolean = false

    private val log = Logger.getLogger(OnboardingScreeningReconciler::class.java)
    private var opened: Counter? = null
    private var failed: Counter? = null
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) {
            val registry = registryInstance.get()
            opened = Counter.builder("openbank.aml.onboarding.cases_opened_by_reconcile")
                .description("Onboarding AML cases opened by the reconciler for a party that had none")
                .tag("service", "aml")
                .register(registry)
            failed = Counter.builder("openbank.aml.onboarding.reconcile_failures")
                .description("Parties the onboarding reconciler could not screen on a tick")
                .tag("service", "aml")
                .register(registry)
        }
        if (enabled) {
            liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        }
    }

    @Scheduled(
        cron = "{openbank.aml.onboarding-reconcile.cron:0 7/15 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun tick() {
        if (!enabled) return
        reconcileOnce()
        liveness?.recordSuccess()
    }

    /** One full pass. Returns how many cases it opened. Party-service being unreachable throws. */
    suspend fun reconcileOnce(): Int {
        val screening = OnboardingScreening(amlUseCase, autoClear)
        var openedNow = 0
        var page = 0
        while (page < maxPages) {
            val batch = parties.listPendingKyc(page, pageSize)
            for (party in batch.items.filter(::isStuck)) {
                if (openIfMissing(screening, party)) openedNow++
            }
            if (!batch.hasMore) break
            page++
        }
        if (page >= maxPages) {
            log.warnf("[onboarding-reconcile] stopped at max-pages=%d; the rest waits for the next tick", maxPages)
        }
        if (openedNow > 0) log.infof("[onboarding-reconcile] opened %d onboarding case(s)", openedNow)
        return openedNow
    }

    private fun isStuck(party: PartySummary): Boolean = party.status == PENDING_KYC &&
        party.kycStatus == KYC_APPROVED &&
        party.partyType in OnboardingScreening.SCREENED_PARTY_TYPES

    @Suppress("TooGenericExceptionCaught")
    private suspend fun openIfMissing(screening: OnboardingScreening, party: PartySummary): Boolean = try {
        if (caseRepository.findByIdempotencyKey(OnboardingScreening.idempotencyKey(party.partyId)) != null) {
            false
        } else {
            val outcome = screening.open(party.partyId)
            opened?.increment()
            log.infof(
                "[onboarding-reconcile] opened onboarding AML case %s for %s party %s (status now %s)",
                outcome.case.id,
                party.partyType,
                party.partyId,
                outcome.case.status,
            )
            true
        }
    } catch (e: Exception) {
        // One party's failure must not starve the rest; the next tick retries it.
        failed?.increment()
        log.warnf(e, "[onboarding-reconcile] could not screen party %s; retried next tick", party.partyId)
        false
    }

    private companion object {
        const val PENDING_KYC = "PENDING_KYC"
        const val KYC_APPROVED = "APPROVED"
        const val DEFAULT_PAGE_SIZE = 100
        const val DEFAULT_MAX_PAGES = 20
        const val WORKFLOW_NAME = "aml-onboarding-reconcile"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
