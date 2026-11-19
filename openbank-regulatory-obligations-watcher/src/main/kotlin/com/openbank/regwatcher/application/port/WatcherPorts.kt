// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.application.port

import com.openbank.regwatcher.domain.FindingDisposition
import com.openbank.regwatcher.domain.Obligation
import com.openbank.regwatcher.domain.SourceBaseline
import com.openbank.regwatcher.domain.SourceFetch
import com.openbank.regwatcher.domain.WatchAudit
import com.openbank.regwatcher.domain.WatchFinding
import java.time.Instant

/** The packaged obligations register (read-only; the watcher never writes it). */
interface ObligationRegisterPort {
    fun obligations(): List<Obligation>
}

/** `web.fetch` on the fetch broker (ADR-0387 D1). Never throws: a failure is an outcome. */
interface WebFetchPort {
    suspend fun fetch(url: String): SourceFetch
}

/** The model call. Input is the obligation rows + the untrusted envelope; output is a closed schema. */
interface ObligationAssessorPort {
    /** A source's content changed. Null when no model answer could be obtained or parsed. */
    suspend fun assessChange(obligations: List<Obligation>, fetch: SourceFetch): Assessment?

    /** A `cadence_confidence: unverified` row against its source. Null as above. */
    suspend fun assessCadence(obligation: Obligation, fetch: SourceFetch): Assessment?

    val modelId: String
    val promptHash: String

    /** Upper bound of one call's tokens (prompt + answer), checked against tokens_per_run BEFORE calling. */
    val maxTokensPerCall: Int
}

/**
 * The parsed model answer. [verdict] is from a closed vocabulary; [quote] has been verified to be
 * a verbatim passage of the fetched text (a quote the page does not contain is rejected upstream).
 */
data class Assessment(
    val verdict: String,
    val summary: String,
    val quote: String?,
    val obligationIds: List<String>,
    val tokensUsed: Int,
)

/** draft.ticket — the ONLY write this agent has. Returns the issue URL, or null when not filed. */
interface GovernanceIssuePort {
    suspend fun fileOrReuse(finding: WatchFinding): IssueResult
}

sealed interface IssueResult {
    data class Filed(val url: String) : IssueResult

    data class Duplicate(val url: String) : IssueResult

    data class NotFiled(val reason: String) : IssueResult
}

/** The OPA sidecar decision for `draft.ticket` (ADR-0031 D9 tier/resource grant). Fails closed. */
interface DraftTicketPolicyPort {
    suspend fun decide(): PolicyVerdict
}

data class PolicyVerdict(val allow: Boolean, val reason: String)

interface WatcherStatePort {
    suspend fun baseline(url: String): SourceBaseline?

    suspend fun saveBaseline(baseline: SourceBaseline)

    suspend fun hasFinding(fingerprint: String): Boolean

    /** The issue an earlier run filed or reused for this fingerprint, if any. */
    suspend fun issueFor(fingerprint: String): String?

    suspend fun recordFinding(
        finding: WatchFinding,
        disposition: FindingDisposition,
        issueUrl: String?,
        audit: WatchAudit,
    )

    suspend fun runsStartedSince(since: Instant): Int

    suspend fun recordRunStart(at: Instant)
}
