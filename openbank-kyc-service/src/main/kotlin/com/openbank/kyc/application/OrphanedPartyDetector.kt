// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.PartyDirectoryPort
import com.openbank.kyc.application.port.out.PartySummary
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Cross-service reconciliation for the invariant `every party has a KYC case` (issue #5698).
 *
 * ### The defect this detects
 *
 * `PartyEventConsumer.handleCreated` caught every exception, logged it and returned — which **acks**
 * the Kafka message. A few seconds of `kyc-db` downtime therefore destroyed the only copy of a
 * `PARTY_CREATED` event: no case was opened, the party stayed `PENDING_KYC` forever, its accounts
 * stayed `PENDING_ACTIVATION`, and nothing anywhere went red. Ten of 73 sandbox parties were in that
 * state, the oldest since 2026-06-07, and it was found only because a human noticed an account did
 * not work. The consumer fix (#5699) stops *new* losses by rethrowing so the connector
 * dead-letters; it cannot see a party already stranded, and no retry, DLQ or replay can either —
 * the event is gone. Only comparing the two services' state finds those, which is what this does.
 *
 * ### Why the comparison runs here and reads party-service over REST
 *
 * The two sides live in separate services and separate databases, so there is no join to write. The
 * dependency direction is already settled: kyc-service consumes party events and calls
 * party-service, never the reverse, so enumerating parties from here adds no new coupling, while
 * teaching party-service about KYC cases would. This follows the fleet's established reconciliation
 * shape — a scheduled read-only tie-out that publishes a drift metric and mutates nothing
 * (`BalanceReconciliationScheduler`, ADR-0039; `AccountReconciliationRepository`, ADR-0026).
 *
 * **It deliberately does not remediate.** Opening the missing cases is a separate, human-gated
 * decision on a compliance-adjacent record: the party's `legalName` must be PEP-screened as part of
 * opening a case, and a bulk auto-open would screen historical parties as a side effect of a
 * monitoring job. Detection is the control; remediation is a replay, tracked separately on #5698.
 */
@ApplicationScoped
class OrphanedPartyDetector(
    private val partyDirectory: PartyDirectoryPort,
    private val kycCaseRepository: KycCaseRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.kyc.orphan-detection.grace-period", defaultValue = "PT1H")
    private val gracePeriod: Duration,
    @ConfigProperty(name = "openbank.kyc.orphan-detection.page-size", defaultValue = "100")
    private val pageSize: Int,
    @ConfigProperty(name = "openbank.kyc.orphan-detection.max-pages", defaultValue = "500")
    private val maxPages: Int,
) {

    private val log = Logger.getLogger(OrphanedPartyDetector::class.java)

    /**
     * Scans the whole party register and reports those with no KYC case at all.
     *
     * Throws rather than swallowing: the caller ([com.openbank.kyc.infrastructure.observability.OrphanedPartyGauge])
     * must be able to tell a scan that found nothing from a scan that never completed, because those
     * two states publish the same orphan count and mean opposite things. That distinction is the
     * whole reason [OrphanedPartyReport.partiesScanned] exists.
     */
    @Suppress("NestedBlockDepth")
    suspend fun detect(): OrphanedPartyReport {
        val now = Instant.now(clock)
        val cutoff = now.minus(gracePeriod)
        val candidates = mutableMapOf<UUID, PartySummary>()
        var scanned = 0L
        var page = 0

        var morePages = true
        while (morePages) {
            if (page >= maxPages) {
                // What this warning must NOT do is name the knob whose own value breaks the job.
                // It used to say "raise openbank.kyc.orphan-detection.max-pages" full stop, while
                // the presence lookup below bound one parameter per candidate into a single
                // statement — so following the advice at ~660 pages walked the query into
                // PostgreSQL's 65,535-parameter protocol ceiling and turned an unchecked tail into
                // a hard failure. The query is batched now (KycRepository.idBatches), which is why
                // this can point at the knob at all; the remaining cost is that one pass holds
                // every candidate in memory, so say that instead of implying the cap is free.
                log.warnf(
                    "[orphan-detection] Stopped at the %d-page cap after scanning %d parties — the " +
                        "register has outgrown the cap and the tail was NOT checked, so this pass's " +
                        "orphan count is a FLOOR, not a total. Raising " +
                        "openbank.kyc.orphan-detection.max-pages widens the scan; one pass holds " +
                        "up to page-size x max-pages party summaries in memory, so raise it against " +
                        "the register's actual size (%d pages covers %d) rather than by a round " +
                        "number, and watch the pod's heap on the next tick.",
                    maxPages,
                    scanned,
                    maxPages,
                    maxPages.toLong() * pageSize,
                )
                break
            }
            val result = partyDirectory.listParties(page, pageSize)
            scanned += result.items.size
            result.items.forEach { party ->
                // Ordering matters for correctness of the grace-period test below: a party is a
                // candidate only if it is BOTH in a state that must have a case AND old enough that
                // the event round-trip cannot still be in flight.
                if (party.status.uppercase() !in STATUSES_WITHOUT_EXPECTED_CASE &&
                    party.createdAt.isBefore(cutoff)
                ) {
                    candidates[party.id] = party
                }
            }
            // `total` is the register-wide count, so it bounds the scan without depending on a
            // short page as the stop signal (an offset scan may legitimately return one). An empty
            // page also ends the scan — party-service has nothing further to give.
            morePages = result.items.isNotEmpty() && scanned < result.total
            page++
        }

        // One batched `IN` query rather than a lookup per party: the N+1 shape would put a query per
        // party on kyc-db every tick, which is a monitoring job generating more load than the thing
        // it monitors.
        val withCases = if (candidates.isEmpty()) {
            emptySet()
        } else {
            kycCaseRepository.findPartyIdsWithAnyCase(candidates.keys)
        }
        val orphans = candidates.values
            .filter { it.id !in withCases }
            .sortedBy { it.createdAt }

        return OrphanedPartyReport(
            orphanedPartyIds = orphans.map { it.id },
            oldestOrphanCreatedAt = orphans.firstOrNull()?.createdAt,
            partiesScanned = scanned,
            checkedAt = now,
        )
    }

    private companion object {
        /**
         * Party states where "no KYC case" is legitimate rather than a defect.
         *
         * `CLOSED` and `MERGED` parties may never have had a case, or had one hard-deleted once the
         * AML 5-year hold expired ([KycCaseRepository.deleteErasedCasesOlderThan], ADR-0118 §5) —
         * flagging those would make the alert fire on correct retention behaviour.
         *
         * `ACTIVE` is deliberately NOT excluded even though it looks settled: a party that reached
         * ACTIVE with no KYC case is a worse finding than a stranded `PENDING_KYC` one, not a
         * benign one, because it means activation happened without the KYC gate.
         *
         * Compared case-insensitively against a raw String so an unrecognised future status
         * degrades to "expected to have a case" — a false positive a human dismisses, rather than a
         * false negative that hides the next incident.
         */
        val STATUSES_WITHOUT_EXPECTED_CASE = setOf("CLOSED", "MERGED")
    }
}

/**
 * Outcome of one reconciliation pass.
 *
 * @param partiesScanned how many parties the pass actually examined. This is the denominator, and
 *   it is reported for a reason: a pass that enumerated **zero** parties reports zero orphans, which
 *   is indistinguishable from a healthy register unless the denominator is published alongside. The
 *   fleet has been bitten by exactly this shape — a probe that cannot express its own failure
 *   reports "clean".
 * @param oldestOrphanCreatedAt creation time of the longest-stranded orphan, or `null` when there
 *   are none. Drives the age gauge that tells a fresh mis-configuration apart from the months-old
 *   backlog #5698 found.
 */
data class OrphanedPartyReport(
    val orphanedPartyIds: List<UUID>,
    val oldestOrphanCreatedAt: Instant?,
    val partiesScanned: Long,
    val checkedAt: Instant,
) {
    val orphanCount: Int get() = orphanedPartyIds.size
}
