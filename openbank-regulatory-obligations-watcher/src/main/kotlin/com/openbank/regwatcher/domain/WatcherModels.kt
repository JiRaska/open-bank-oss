// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.domain

import java.security.MessageDigest
import java.time.Instant

/** One row of `regulatory-obligations.yaml`, reduced to what the watcher reads. */
data class Obligation(
    val id: String,
    val name: String,
    val legalBasis: String,
    val cadence: String,
    val cadenceConfidence: String,
    val sources: Map<String, String>,
) {
    val isUnverified: Boolean get() = cadenceConfidence == UNVERIFIED

    /** The source a cadence is best confirmed against: the authority spec, else the law. */
    val cadenceSource: String? get() = sources["spec"] ?: sources["law"]

    companion object {
        const val UNVERIFIED = "unverified"
    }
}

/** Broker outcome (ADR-0387 D8): its own enum, never a boolean. */
enum class SourceOutcome { FETCHED, DENIED, NOT_RENDERABLE }

/** One `web.fetch` answer. [content] is the broker's untrusted-content envelope, verbatim. */
data class SourceFetch(
    val url: String,
    val outcome: SourceOutcome,
    val sha256: String?,
    val content: String?,
    val reason: String?,
    val fetchedAt: Instant,
)

/** Per-URL baseline. [sha256]/[fetchedAt] describe the last FETCHED read only. */
data class SourceBaseline(
    val url: String,
    val sha256: String?,
    val fetchedAt: Instant?,
    val lastOutcome: SourceOutcome,
    val lastAttemptAt: Instant,
    val lastReason: String?,
)

enum class FindingKind {
    /** The source's content hash moved since the last FETCHED baseline and the model saw an amendment. */
    SOURCE_CHANGED,

    /** A `cadence_confidence: unverified` row, with the quote that supports or contradicts it. */
    CADENCE_CONFIRMATION,

    /** The source could not be read (DENIED / NOT_RENDERABLE) — never "unchanged". */
    UNVERIFIED_SOURCE,
}

/** What happened to a finding. A refused or skipped issue is never representable as a filed one. */
enum class FindingDisposition {
    FILED,
    DUPLICATE,
    NOT_FILED_POLICY_DENIED,
    NOT_FILED_WRITER_UNAVAILABLE,
    DEFERRED,
    NO_ACTION,
}

data class WatchFinding(
    val kind: FindingKind,
    val obligationIds: List<String>,
    val sourceUrl: String,
    val sha256: String?,
    val quote: String?,
    val summary: String,
    val verdict: String,
) {
    /** Stable across runs: the same fact on the same page content dedups to one issue. */
    val fingerprint: String = fingerprintOf(kind, sourceUrl, sha256 ?: verdict, obligationIds)

    companion object {
        fun fingerprintOf(kind: FindingKind, url: String, discriminator: String, ids: List<String>): String {
            val raw = listOf(kind.name, url, discriminator, ids.sorted().joinToString(",")).joinToString("|")
            return sha256Hex(raw.toByteArray()).take(FINGERPRINT_CHARS)
        }

        private const val FINGERPRINT_CHARS = 24
    }
}

/** Audit record per finding — the charter's `audit.capture` field set. */
data class WatchAudit(
    val modelId: String?,
    val promptHash: String?,
    val toolCalls: List<String>,
    val policyDecision: String,
    val obligationId: String,
    val sourceUrl: String,
    val sourceQuote: String?,
)

/** Run summary. [fetched] > 0 is the ONLY condition under which liveness success is recorded. */
data class WatchRunResult(
    val sourcesAttempted: Int,
    val fetched: Int,
    val unverified: Int,
    val changed: Int,
    val issuesFiled: Int,
    val deferred: Int,
    val tokensUsed: Int,
    val skippedReason: String? = null,
) {
    val livenessEarned: Boolean get() = skippedReason == null && fetched > 0
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
