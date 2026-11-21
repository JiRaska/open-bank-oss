// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Every column is named explicitly: Hibernate's implicit name is the property name verbatim, and
// Postgres folds `lastOutcome` to `lastoutcome`, a column no migration creates (entity-column-names).

@Entity
@Table(name = "source_baselines")
class SourceBaselineEntity {
    @Id
    @Column(name = "url", nullable = false)
    lateinit var url: String

    @Column(name = "sha256")
    var sha256: String? = null

    @Column(name = "fetched_at")
    var fetchedAt: Instant? = null

    @Column(name = "last_outcome", nullable = false)
    lateinit var lastOutcome: String

    @Column(name = "last_attempt_at", nullable = false)
    lateinit var lastAttemptAt: Instant

    @Column(name = "last_reason")
    var lastReason: String? = null
}

@Entity
@Table(name = "watch_findings")
class WatchFindingEntity {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: UUID

    @Column(name = "fingerprint", nullable = false)
    lateinit var fingerprint: String

    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "verdict", nullable = false)
    lateinit var verdict: String

    @Column(name = "disposition", nullable = false)
    lateinit var disposition: String

    @Column(name = "issue_url")
    var issueUrl: String? = null

    @Column(name = "obligation_id", nullable = false, columnDefinition = "TEXT")
    lateinit var obligationId: String

    @Column(name = "source_url", nullable = false)
    lateinit var sourceUrl: String

    @Column(name = "sha256")
    var sha256: String? = null

    @Column(name = "source_quote", columnDefinition = "TEXT")
    var sourceQuote: String? = null

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    lateinit var summary: String

    @Column(name = "model_id")
    var modelId: String? = null

    @Column(name = "prompt_hash")
    var promptHash: String? = null

    @Column(name = "tool_calls", nullable = false)
    lateinit var toolCalls: String

    @Column(name = "policy_decision", nullable = false)
    lateinit var policyDecision: String

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant
}

@Entity
@Table(name = "watch_runs")
class WatchRunEntity {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: UUID

    @Column(name = "started_at", nullable = false)
    lateinit var startedAt: Instant
}
