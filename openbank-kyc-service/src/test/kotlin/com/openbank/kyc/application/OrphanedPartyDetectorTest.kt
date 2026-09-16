// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.PartyDirectoryPage
import com.openbank.kyc.application.port.out.PartyDirectoryPort
import com.openbank.kyc.application.port.out.PartySummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit coverage for the #5698 detection rule.
 *
 * Deliberately drives [OrphanedPartyDetector.detect] directly with mocked ports — this asserts the
 * *rule* (who counts as orphaned). That a real cron actually dispatches the job is a different
 * claim, which a mocked test structurally cannot make; `OrphanedPartyDetectionSchedulerIT` owns it.
 */
class OrphanedPartyDetectorTest {

    private val now: Instant = Instant.parse("2026-08-19T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val directory = mockk<PartyDirectoryPort>()
    private val repository = mockk<KycCaseRepository>()

    private fun detector(gracePeriod: Duration = Duration.ofHours(1)) = OrphanedPartyDetector(
        partyDirectory = directory,
        kycCaseRepository = repository,
        clock = clock,
        gracePeriod = gracePeriod,
        pageSize = PAGE_SIZE,
        maxPages = MAX_PAGES,
    )

    private fun party(status: String = "PENDING_KYC", ageMinutes: Long = 120) = PartySummary(
        id = UUID.randomUUID(),
        status = status,
        createdAt = now.minus(Duration.ofMinutes(ageMinutes)),
    )

    private fun directoryHolds(vararg parties: PartySummary) {
        coEvery { directory.listParties(0, PAGE_SIZE) } returns
            PartyDirectoryPage(items = parties.toList(), total = parties.size.toLong())
    }

    private fun casesExistFor(vararg ids: UUID) {
        coEvery { repository.findPartyIdsWithAnyCase(any()) } returns ids.toSet()
    }

    /**
     * Default: no cutoff available, so nothing is excluded as pre-consumer. That is the
     * conservative direction the detector must take for an empty store, and it keeps every case
     * below about the rule it was written for rather than about #9726's eligibility split.
     */
    @BeforeEach
    fun noEligibilityCutoffByDefault() {
        coEvery { repository.earliestCaseCreatedAt() } returns null
    }

    private fun oldestCaseAt(at: Instant) {
        coEvery { repository.earliestCaseCreatedAt() } returns at
    }

    @Test
    fun `a party older than the grace period with no KYC case is detected`(): Unit = runBlocking {
        val stranded = party(ageMinutes = 120)
        directoryHolds(stranded)
        casesExistFor()

        val report = detector().detect()

        assertThat(report.orphanedPartyIds)
            .describedAs(
                "the #5698 defect exactly: a party whose PARTY_CREATED was acked and dropped has no " +
                    "case and nothing replays it, so only this comparison can find it",
            )
            .containsExactly(stranded.id)
        assertThat(report.oldestOrphanCreatedAt).isEqualTo(stranded.createdAt)
        assertThat(report.partiesScanned).isEqualTo(1L)
    }

    @Test
    fun `a party still inside the grace period is NOT flagged`(): Unit = runBlocking {
        // Created 5 minutes ago: its PARTY_CREATED may still legitimately be in flight. Flagging
        // this would make the alert fire on every new signup, which is worse than no alert.
        val fresh = party(ageMinutes = 5)
        directoryHolds(fresh)
        casesExistFor()

        val report = detector(gracePeriod = Duration.ofHours(1)).detect()

        assertThat(report.orphanedPartyIds).isEmpty()
        assertThat(report.oldestOrphanCreatedAt).isNull()
        assertThat(report.partiesScanned)
            .describedAs("it was still scanned — the denominator counts it, the numerator does not")
            .isEqualTo(1L)
    }

    @Test
    fun `a party that has a KYC case is NOT flagged`(): Unit = runBlocking {
        val handled = party(ageMinutes = 500)
        directoryHolds(handled)
        casesExistFor(handled.id)

        val report = detector().detect()

        assertThat(report.orphanedPartyIds).isEmpty()
        assertThat(report.partiesScanned).isEqualTo(1L)
    }

    @Test
    fun `a CLOSED or MERGED party with no case is NOT flagged, but an ACTIVE one is`(): Unit = runBlocking {
        val closed = party(status = "CLOSED", ageMinutes = 5_000)
        val merged = party(status = "MERGED", ageMinutes = 5_000)
        val active = party(status = "ACTIVE", ageMinutes = 5_000)
        directoryHolds(closed, merged, active)
        casesExistFor()

        val report = detector().detect()

        assertThat(report.orphanedPartyIds)
            .describedAs(
                "CLOSED/MERGED may legitimately have no case (or had one hard-deleted once the AML " +
                    "5-year hold expired), so flagging them would alert on correct retention. ACTIVE " +
                    "is the opposite: it means the party was activated without the KYC gate.",
            )
            .containsExactly(active.id)
    }

    @Test
    fun `an unrecognised party status is treated as expecting a case`(): Unit = runBlocking {
        // party-service owns this vocabulary and has added values its own OpenAPI enum omits. An
        // unknown status must degrade to a false positive a human dismisses, never to a false
        // negative that hides the next incident.
        val unknown = party(status = "SOME_FUTURE_STATE", ageMinutes = 5_000)
        directoryHolds(unknown)
        casesExistFor()

        assertThat(detector().detect().orphanedPartyIds).containsExactly(unknown.id)
    }

    @Test
    fun `paging continues until the register total is covered and de-duplicates`(): Unit = runBlocking {
        val first = party(ageMinutes = 300)
        val second = party(ageMinutes = 200)
        // Same party returned on both pages — an offset scan can shift under a concurrent insert.
        coEvery { directory.listParties(0, PAGE_SIZE) } returns
            PartyDirectoryPage(items = listOf(first, second), total = 3)
        coEvery { directory.listParties(1, PAGE_SIZE) } returns
            PartyDirectoryPage(items = listOf(second), total = 3)
        casesExistFor()

        val report = detector().detect()

        assertThat(report.orphanedPartyIds)
            .describedAs("a party seen on two pages must be reported once, not twice")
            .containsExactlyInAnyOrder(first.id, second.id)
    }

    @Test
    fun `an empty register reports no orphans and a zero denominator`(): Unit = runBlocking {
        coEvery { directory.listParties(0, PAGE_SIZE) } returns PartyDirectoryPage(emptyList(), 0)

        val report = detector().detect()

        assertThat(report.orphanCount).isZero()
        assertThat(report.partiesScanned)
            .describedAs(
                "publishing the denominator is what separates 'nothing is wrong' from 'this control " +
                    "is looking at nothing' — both report zero orphans",
            )
            .isZero()
    }

    @Test
    fun `a party created before the oldest case is pre-consumer, not stranded`(): Unit = runBlocking {
        // The sandbox's six 2026-06-07 parties (#9726): PartyEventConsumer did not exist yet, so no
        // case was opened for them and none ever could have been. Counting them as stranded put a
        // permanent floor under the gauge and made `> 0` unsatisfiable.
        val preConsumer = party(ageMinutes = 10_000)
        val stranded = party(ageMinutes = 1_000)
        directoryHolds(preConsumer, stranded)
        casesExistFor()
        oldestCaseAt(now.minus(Duration.ofMinutes(5_000)))

        val report = detector().detect()

        assertThat(report.orphanedPartyIds).containsExactly(stranded.id)
        assertThat(report.preConsumerPartyIds).containsExactly(preConsumer.id)
        assertThat(report.oldestOrphanCreatedAt)
            .describedAs("the age gauge must age the ACTIONABLE orphan, not the excluded one")
            .isEqualTo(stranded.createdAt)
    }

    @Test
    fun `with no case in the store NOTHING is excluded`(): Unit = runBlocking {
        // A wiped or brand-new kyc-db has no cutoff to derive. Excluding on a null cutoff would let
        // an empty store report a clean register — the report-clean failure #5698 is about.
        val ancient = party(ageMinutes = 100_000)
        directoryHolds(ancient)
        casesExistFor()
        coEvery { repository.earliestCaseCreatedAt() } returns null

        val report = detector().detect()

        assertThat(report.orphanedPartyIds).containsExactly(ancient.id)
        assertThat(report.preConsumerPartyIds).isEmpty()
    }

    @Test
    fun `a party created at the same instant as the oldest case is NOT excluded`(): Unit = runBlocking {
        // The boundary is strict: the cutoff is the first moment the auto-open path is known to
        // have worked, so a party created AT that instant was eligible and its missing case is real.
        val at = now.minus(Duration.ofMinutes(5_000))
        val boundary = PartySummary(id = UUID.randomUUID(), status = "PENDING_KYC", createdAt = at)
        directoryHolds(boundary)
        casesExistFor()
        oldestCaseAt(at)

        val report = detector().detect()

        assertThat(report.orphanedPartyIds).containsExactly(boundary.id)
        assertThat(report.preConsumerPartyIds).isEmpty()
    }

    @Test
    fun `the cutoff excludes only parties with no case, never parties that have one`(): Unit = runBlocking {
        // The split runs AFTER the case lookup, so a pre-consumer party that does have a case is
        // not an orphan at all and must appear in neither list.
        val oldButHandled = party(ageMinutes = 10_000)
        directoryHolds(oldButHandled)
        casesExistFor(oldButHandled.id)
        oldestCaseAt(now.minus(Duration.ofMinutes(5_000)))

        val report = detector().detect()

        assertThat(report.orphanedPartyIds).isEmpty()
        assertThat(report.preConsumerPartyIds).isEmpty()
        assertThat(report.firstCaseCreatedAt).isNotNull()
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 500
    }
}
