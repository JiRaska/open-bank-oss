// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.infrastructure.scheduler

import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.infrastructure.client.AccountServiceClient
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * Points AML cases at the party that actually owns the account (#3413).
 *
 * ### The defect
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
 * **Not "make `partyId` nullable".** Measured with `oasdiff` against this service's own
 * `openapi.yaml`: dropping it from the response's `required` is `response-property-became-optional`
 * and keeping it required-but-`nullable: true` is `response-property-became-nullable` — **both**
 * classify as breaking, so either costs a MAJOR bump and a `/api/v2/aml/cases` URL. And a null
 * fixes none of the three things above; it only stops writing a wrong value.
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
 * `suspend`, never `runBlocking` (#2148); the lookup runs on [Dispatchers.IO] because a blocking
 * HTTP call on the scheduler's Vert.x context stalls the event loop and, under
 * `ConcurrentExecution.SKIP`, one stalled tick wedges every later one.
 */
@Startup
@ApplicationScoped
class PartyResolutionScheduler {

    @Inject
    lateinit var caseRepository: AmlCaseRepository

    @Inject
    lateinit var accounts: AccountServiceClient

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    @ConfigProperty(name = "openbank.aml.party-resolution.enabled", defaultValue = "true")
    var enabled: Boolean = true

    @ConfigProperty(name = "openbank.aml.party-resolution.batch-limit", defaultValue = "50")
    var batchLimit: Int = DEFAULT_BATCH_LIMIT

    private val log = Logger.getLogger(PartyResolutionScheduler::class.java)
    private val unresolved = AtomicLong(0)

    @PostConstruct
    fun register() {
        if (!registryInstance.isResolvable) return
        Gauge.builder("openbank.aml.cases.party_unresolved", unresolved) { it.get().toDouble() }
            .tag("service", "aml")
            .strongReference(true)
            .register(registryInstance.get())
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
            log.infof("[party-resolution] resolving %d case(s) still holding an account id", batch.size)
            for ((caseId, accountId) in batch) {
                val partyId = withContext(Dispatchers.IO) { accounts.findPartyByAccountId(accountId) }
                when {
                    partyId == null ->
                        log.warnf(
                            "[party-resolution] account %s did not resolve; case %s left as is",
                            accountId,
                            caseId,
                        )
                    partyId == accountId ->
                        // Would leave the row indistinguishable from unresolved and re-swept forever.
                        log.errorf(
                            "[party-resolution] account %s reports itself as its own party — refusing",
                            accountId,
                        )
                    else -> {
                        caseRepository.resolveParty(caseId, partyId)
                        log.infof("[party-resolution] case %s → party %s", caseId, partyId)
                    }
                }
            }
        }
        // Recomputed every tick, including a tick that resolved nothing, so the gauge reflects the
        // backlog rather than the last batch size.
        unresolved.set(caseRepository.countUnresolvedParty())
    }

    private companion object {
        const val DEFAULT_BATCH_LIMIT = 50
    }
}
