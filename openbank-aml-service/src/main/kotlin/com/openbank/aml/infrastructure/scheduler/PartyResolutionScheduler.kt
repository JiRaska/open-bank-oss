// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.scheduler

import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.application.usecase.CasePartyResolver
import com.openbank.aml.infrastructure.client.AccountServiceClient
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Points AML cases at the party that actually owns the account.
 *
 * Two kinds of case reach it, and it treats them alike. A held payment whose rail could not name
 * the debtor's party is opened with NO party (`party_id IS NULL`): the hold intake never asks the
 * account authority, because that authority being unreachable is the usual reason the party is
 * missing. And the older rows described below, which carry the account id where the party belongs.
 * Both know their account, and the owning party is one lookup away once the authority answers.
 *
 * A lookup that does not succeed stamps the case with the attempt time and the batch is read least
 * recently attempted first. Without that, cases that can never resolve (an account the authority
 * does not know) would fill every tick's batch and the resolvable ones behind them would wait
 * forever, while the tick itself kept reporting success.
 *
 * ### The #3413 defect
 *
 * `aml_cases.party_id` is `NOT NULL`, and a payment carries only `debtorAccountId` — so the rails
 * satisfied the constraint with the ACCOUNT id. It is a valid non-null UUID, nothing detected it,
 * and it joins to no party. What that breaks is party-scoped: per-customer aggregation (a client
 * with three accounts reads as three unrelated subjects — the pattern structuring detection exists
 * to catch), the GDPR Art. 15 `?partyId=` subject-access query, and `anonymizeByPartyId`, the
 * Art. 17 erasure path. Measured: on all 6 payment-path cases `party_id` equals `account_id`
 * exactly, so nothing is lost by replacing it — the real party is one lookup away.
 *
 * ### Why a sweep, and not the two designs that were tried first
 *
 * **Not "make the REST response's `partyId` nullable".** Measured with `oasdiff` against this service's
 * own `openapi.yaml`: dropping it from the response's `required` is
 * `response-property-became-optional` and keeping it required-but-`nullable: true` is
 * `response-property-became-nullable` — **both** classify as breaking, so either costs a MAJOR bump
 * and a `/api/v2/aml/cases` URL. That binds the REST response only: events publish `null`, and the
 * STORED party may be missing, and
 * for a held payment it is; the response then says `partyResolved: false` beside a `partyId` that
 * is the nil UUID, an additive change. And a null alone fixes none of the three things above; it
 * only stops writing a wrong value, which is why the sweep exists either way.
 *
 * **Not a Kafka projection off `openbank.accounts.account.created`.** That topic is
 * `cleanup.policy: delete` with 7-day retention and is not compacted, while most of the 77 accounts
 * are older — so a fresh consumer could never learn their party, and the projection would look like
 * it worked while resolving almost nothing.
 *
 * **Not resolution at ingest.** A case must never fail to be recorded because account-service is
 * unreachable. Resolution is therefore something that happens to a stored row afterwards; the
 * case-creation path is untouched by this class.
 *
 * ### Not silently wrong
 *
 * `openbank_aml_cases_party_unresolved` is published so the sweep cannot quietly resolve nothing —
 * the failure this repo keeps meeting is a mechanism that reads as working while doing nothing.
 *
 * `suspend`, never `runBlocking` (#2148). The account lookup is a reactive REST client call (a
 * `Uni`, awaited), so it runs on the scheduler's own Vert.x context and never blocks it; nothing
 * here may become a blocking call, because under `ConcurrentExecution.SKIP` one stalled tick wedges
 * every later one.
 */
@Startup
@ApplicationScoped
class PartyResolutionScheduler {

    @Inject
    lateinit var caseRepository: AmlCaseRepository

    @Inject
    lateinit var resolver: CasePartyResolver

    @Inject
    lateinit var accounts: AccountServiceClient

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @ConfigProperty(name = "openbank.aml.party-resolution.enabled", defaultValue = "true")
    var enabled: Boolean = true

    @ConfigProperty(name = "openbank.aml.party-resolution.batch-limit", defaultValue = "50")
    var batchLimit: Int = DEFAULT_BATCH_LIMIT

    private val log = Logger.getLogger(PartyResolutionScheduler::class.java)
    private val unresolved = AtomicLong(0)
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) {
            Gauge.builder("openbank.aml.cases.party_unresolved", unresolved) { it.get().toDouble() }
                .tag("service", "aml")
                .strongReference(true)
                .register(registryInstance.get())
        }
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "{openbank.aml.party-resolution.interval:30m}",
        delayed = "{openbank.aml.party-resolution.initial-delay:2m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sweep() {
        if (!enabled) return
        val batch = caseRepository.findUnresolvedParty(batchLimit)
        if (batch.isNotEmpty()) {
            log.infof("[party-resolution] resolving %d case(s) with no established party", batch.size)
            for ((caseId, accountId) in batch) resolveOne(caseId, accountId)
        }
        // Recomputed every tick, including a tick that resolved nothing, so the gauge reflects the
        // backlog rather than the last batch size.
        unresolved.set(caseRepository.countUnresolvedParty())
        liveness?.recordSuccess()
    }

    private suspend fun resolveOne(caseId: UUID, accountId: UUID) {
        val partyId = accounts.findPartyByAccountId(accountId)
        when {
            partyId == null -> {
                caseRepository.markPartyResolutionAttempted(caseId, Instant.now())
                log.warnf("[party-resolution] account %s did not resolve; case %s left as is", accountId, caseId)
            }
            partyId == accountId -> {
                // Would leave the row indistinguishable from unresolved and re-swept forever.
                caseRepository.markPartyResolutionAttempted(caseId, Instant.now())
                log.errorf("[party-resolution] account %s reports itself as its own party — refusing", accountId)
            }
            // One transaction: the party, a BLOCK the party was owed, and the anonymisation owed if
            // the party was erased meanwhile.
            resolver.resolve(caseId, partyId) -> log.infof("[party-resolution] case %s → party %s", caseId, partyId)
        }
    }

    private companion object {
        const val DEFAULT_BATCH_LIMIT = 50
        const val WORKFLOW_NAME = "aml-party-resolution"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(30)
    }
}
