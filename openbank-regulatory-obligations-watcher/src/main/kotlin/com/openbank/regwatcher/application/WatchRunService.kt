// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.application

import com.openbank.regwatcher.application.port.Assessment
import com.openbank.regwatcher.application.port.DraftTicketPolicyPort
import com.openbank.regwatcher.application.port.GovernanceIssuePort
import com.openbank.regwatcher.application.port.IssueResult
import com.openbank.regwatcher.application.port.ObligationAssessorPort
import com.openbank.regwatcher.application.port.ObligationRegisterPort
import com.openbank.regwatcher.application.port.WatcherStatePort
import com.openbank.regwatcher.application.port.WebFetchPort
import com.openbank.regwatcher.domain.FindingDisposition
import com.openbank.regwatcher.domain.FindingKind
import com.openbank.regwatcher.domain.Obligation
import com.openbank.regwatcher.domain.SourceBaseline
import com.openbank.regwatcher.domain.SourceFetch
import com.openbank.regwatcher.domain.SourceOutcome
import com.openbank.regwatcher.domain.WatchAudit
import com.openbank.regwatcher.domain.WatchFinding
import com.openbank.regwatcher.domain.WatchRunResult
import java.time.Clock
import java.time.ZoneOffset

/** The charter limits this run enforces (agents.yaml: regulatory-obligations-watcher.limits). */
data class WatcherLimits(val tokensPerRun: Int, val runsPerDay: Int, val maxIssuesPerRun: Int)

/** Everything one run does with a source that was read. Not exposed: the run's own bookkeeping. */
private class RunTally {
    var fetched = 0
    var unverified = 0
    var changed = 0
    var filed = 0
    var deferred = 0
    var tokens = 0
}

/**
 * One watcher run (ADR-0367 decision 6, ADR-0387 S4).
 *
 * For every source URL the register cites, it asks the broker for the page and compares the
 * content hash with the last FETCHED baseline:
 *  - the hash moved → the model compares the citing obligation rows with the text and, unless it
 *    sees no material change, ONE governance issue is drafted for that URL;
 *  - a `cadence_confidence: unverified` row → a confirmation proposal carrying the quote that
 *    supports or contradicts the row (once per page content);
 *  - DENIED / NOT_RENDERABLE → an UNVERIFIED finding. A page that could not be read is never
 *    reported as unchanged, and never overwrites the last good hash.
 *
 * Output is draft.ticket only. A finding the run could not complete (budget, issue cap, no model
 * answer, writer refused) is DEFERRED and its baseline is NOT advanced, so the next run retries it
 * instead of silently absorbing the change.
 */
class WatchRunService(
    private val register: ObligationRegisterPort,
    private val web: WebFetchPort,
    private val assessor: ObligationAssessorPort,
    private val issues: GovernanceIssuePort,
    private val policy: DraftTicketPolicyPort,
    private val state: WatcherStatePort,
    private val limits: WatcherLimits,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Volatile
    var killed: Boolean = false

    @Suppress("ReturnCount")
    suspend fun run(): WatchRunResult {
        if (killed) return skipped("kill switch engaged")
        val now = clock.instant()
        val startOfDay = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
        if (state.runsStartedSince(startOfDay) >= limits.runsPerDay) return skipped("runs_per_day limit reached")
        state.recordRunStart(now)

        val bySource = linkedMapOf<String, MutableList<Obligation>>()
        register.obligations().forEach { o ->
            o.sources.values.forEach { bySource.getOrPut(it) { mutableListOf() }.add(o) }
        }

        val tally = RunTally()
        for ((url, citing) in bySource.toSortedMap()) {
            if (killed) break
            watchSource(url, citing, tally)
        }
        return WatchRunResult(
            sourcesAttempted = bySource.size,
            fetched = tally.fetched,
            unverified = tally.unverified,
            changed = tally.changed,
            issuesFiled = tally.filed,
            deferred = tally.deferred,
            tokensUsed = tally.tokens,
        )
    }

    private suspend fun watchSource(url: String, citing: List<Obligation>, tally: RunTally) {
        val fetch = web.fetch(url)
        val previous = state.baseline(url)
        if (fetch.outcome != SourceOutcome.FETCHED) {
            tally.unverified++
            handleUnreadable(fetch, citing, previous, tally)
            return
        }
        tally.fetched++
        val advance = when {
            previous?.sha256 == null -> true // first read: this IS the baseline
            previous.sha256 == fetch.sha256 -> true
            else -> {
                tally.changed++
                handleChanged(fetch, citing, tally)
            }
        }
        state.saveBaseline(
            SourceBaseline(
                url = url,
                sha256 = if (advance) fetch.sha256 else previous?.sha256,
                fetchedAt = if (advance) fetch.fetchedAt else previous?.fetchedAt,
                lastOutcome = SourceOutcome.FETCHED,
                lastAttemptAt = fetch.fetchedAt,
                lastReason = null,
            ),
        )
        citing.filter { it.isUnverified && it.cadenceSource == url }.forEach { handleCadence(it, fetch, tally) }
    }

    private suspend fun handleUnreadable(
        fetch: SourceFetch,
        citing: List<Obligation>,
        previous: SourceBaseline?,
        tally: RunTally,
    ) {
        val finding = WatchFinding(
            kind = FindingKind.UNVERIFIED_SOURCE,
            obligationIds = citing.map { it.id }.distinct(),
            sourceUrl = fetch.url,
            sha256 = null,
            quote = null,
            summary = "The source could not be read (${fetch.outcome}: ${fetch.reason ?: "no reason given"}). " +
                "Whether it changed is UNVERIFIED — it is not unchanged.",
            verdict = fetch.outcome.name,
        )
        deliver(finding, model = false, tally)
        state.saveBaseline(
            SourceBaseline(
                url = fetch.url,
                sha256 = previous?.sha256,
                fetchedAt = previous?.fetchedAt,
                lastOutcome = fetch.outcome,
                lastAttemptAt = fetch.fetchedAt,
                lastReason = fetch.reason,
            ),
        )
    }

    /** Returns whether the baseline may advance to the new hash. */
    private suspend fun handleChanged(fetch: SourceFetch, citing: List<Obligation>, tally: RunTally): Boolean {
        val assessment = assess(tally) { assessor.assessChange(citing, fetch) } ?: return false
        val ids = assessment.obligationIds.filter { id -> citing.any { it.id == id } }.ifEmpty { citing.map { it.id } }
        val finding = WatchFinding(
            kind = FindingKind.SOURCE_CHANGED,
            obligationIds = ids.distinct(),
            sourceUrl = fetch.url,
            sha256 = fetch.sha256,
            quote = assessment.quote,
            summary = assessment.summary,
            verdict = assessment.verdict,
        )
        if (assessment.verdict == NO_MATERIAL_CHANGE) {
            state.recordFinding(
                finding,
                FindingDisposition.NO_ACTION,
                null,
                audit(finding, "not evaluated", model = true),
            )
            return true
        }
        return deliver(finding, model = true, tally)
    }

    private suspend fun handleCadence(obligation: Obligation, fetch: SourceFetch, tally: RunTally) {
        val probe = WatchFinding.fingerprintOf(
            FindingKind.CADENCE_CONFIRMATION,
            fetch.url,
            fetch.sha256 ?: "",
            listOf(obligation.id),
        )
        if (state.hasFinding(probe)) return // this row was already assessed against this exact content
        val assessment = assess(tally) { assessor.assessCadence(obligation, fetch) } ?: return
        val finding = WatchFinding(
            kind = FindingKind.CADENCE_CONFIRMATION,
            obligationIds = listOf(obligation.id),
            sourceUrl = fetch.url,
            sha256 = fetch.sha256,
            quote = assessment.quote,
            summary = assessment.summary,
            verdict = assessment.verdict,
        )
        if (assessment.verdict == NOT_FOUND) {
            state.recordFinding(
                finding,
                FindingDisposition.NO_ACTION,
                null,
                audit(finding, "not evaluated", model = true),
            )
            return
        }
        deliver(finding, model = true, tally)
    }

    private suspend fun assess(tally: RunTally, call: suspend () -> Assessment?): Assessment? {
        if (tally.tokens + assessor.maxTokensPerCall > limits.tokensPerRun || tally.filed >= limits.maxIssuesPerRun) {
            tally.deferred++
            return null
        }
        val a = call()
        if (a == null) {
            tally.deferred++
            return null
        }
        tally.tokens += a.tokensUsed
        return a
    }

    /** Files (or reuses) the issue. Returns true only when an issue now exists for the finding. */
    private suspend fun deliver(finding: WatchFinding, model: Boolean, tally: RunTally): Boolean {
        if (tally.filed >= limits.maxIssuesPerRun) {
            tally.deferred++
            state.recordFinding(finding, FindingDisposition.DEFERRED, null, audit(finding, "not evaluated", model))
            return false
        }
        state.issueFor(finding.fingerprint)?.let { existing ->
            // Already filed by an earlier run (closed or not): never file the same fact twice.
            state.recordFinding(finding, FindingDisposition.DUPLICATE, existing, audit(finding, "not evaluated", model))
            return true
        }
        val decision = policy.decide()
        if (!decision.allow) {
            tally.deferred++
            state.recordFinding(
                finding,
                FindingDisposition.NOT_FILED_POLICY_DENIED,
                null,
                audit(finding, "deny: ${decision.reason}", model),
            )
            return false
        }
        val result = issues.fileOrReuse(finding)
        val (disposition, url) = when (result) {
            is IssueResult.Filed -> FindingDisposition.FILED to result.url
            is IssueResult.Duplicate -> FindingDisposition.DUPLICATE to result.url
            is IssueResult.NotFiled -> FindingDisposition.NOT_FILED_WRITER_UNAVAILABLE to null
        }
        if (result is IssueResult.Filed) tally.filed++
        if (result is IssueResult.NotFiled) tally.deferred++
        state.recordFinding(finding, disposition, url, audit(finding, "allow: ${decision.reason}", model, issue = true))
        return url != null
    }

    private fun audit(finding: WatchFinding, policyDecision: String, model: Boolean, issue: Boolean = false) =
        WatchAudit(
            modelId = if (model) assessor.modelId else null,
            promptHash = if (model) assessor.promptHash else null,
            toolCalls = listOfNotNull("web.fetch", if (issue) "draft.ticket" else null),
            policyDecision = policyDecision,
            obligationId = finding.obligationIds.joinToString(","),
            sourceUrl = finding.sourceUrl,
            sourceQuote = finding.quote,
        )

    private fun skipped(reason: String) = WatchRunResult(0, 0, 0, 0, 0, 0, 0, skippedReason = reason)

    companion object {
        const val NO_MATERIAL_CHANGE = "NO_MATERIAL_CHANGE"
        const val NOT_FOUND = "NOT_FOUND"
    }
}
