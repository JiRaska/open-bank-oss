// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.port.out

import com.openbank.docstruth.domain.model.AdrRecord
import com.openbank.docstruth.domain.model.ArtifactExistence
import com.openbank.docstruth.domain.model.DocsTruthFinding

/**
 * Direct repo-checkout read of every ADR under `docs/adr` (`read.governance`, ADR-0166 checks 1-2)
 * and a lightweight, repo-wide grep/find for the artifacts those ADRs claim — existence and basic
 * wiring, not deep semantic verification (ADR-0166 Decision). This agent runs from within the
 * monorepo, so a full re-implementation of a code-intelligence index is not needed for this check.
 */
interface RepoScanPort {
    /** Parses every ADR file under `docs/adr` into an [AdrRecord]: id, title, `Delivery-Status:`,
     * and every backtick-quoted artifact/gate reference the ADR makes, tagged with its textual
     * context (a "ships as" claim vs. a "not yet built" claim). */
    suspend fun scanAdrRecords(): List<AdrRecord>

    /** A single repo-wide pass (excluding `docs/adr` itself, to avoid trivially matching an
     * ADR's own prose) checking whether each of [artifacts] appears anywhere in the repo, and
     * where. Batched rather than one call per artifact so the whole fleet is walked once per run. */
    suspend fun findArtifacts(artifacts: Set<String>): Map<String, ArtifactExistence>
}

/**
 * Reads `openbank-libs/governance/rules.yaml`'s gate-graduation `enforced:` status (ADR-0144,
 * `read.governance`, ADR-0166 check 3) — split out from [RepoScanPort] because it targets a
 * single, structured governance file rather than free-text ADR prose.
 */
interface GovernanceRulesPort {
    /** Best-effort: for each of [gateNamesOrScripts], the `enforced:` value found textually
     * nearest a line mentioning that name in `rules.yaml` (mirroring how the file's own comments
     * reference a gate's script name right next to its `enforced:` line). A name with no nearby
     * match is absent from the result map. */
    suspend fun enforcedStatusFor(gateNamesOrScripts: Set<String>): Map<String, String>
}

interface LlmDiagnosisPort {
    suspend fun diagnose(finding: DocsTruthFinding, contextMetrics: Map<String, Double>): String
    suspend fun proposeFixDiff(finding: DocsTruthFinding, diagnosis: String): String?
}

/**
 * Both methods return the URL of a proposal that was actually created, or `null` when none was —
 * an unwired write path, a missing token, or a refused finding. `null` is the ONLY way to say
 * "nothing was created": there is deliberately no placeholder-URL return, because a well-formed
 * string is indistinguishable from a delivered proposal to every consumer (#5897, and the
 * `UnwiredProposalPort` precedent in `openbank-mcp-service`, #3900).
 */
interface GitHubProposalPort {
    /** The one narrow mechanically-fixable case — flipping just the `Delivery-Status:` line when
     * the evidence is unambiguous. Not the primary proposal path: correcting an ADR's substantive
     * content needs a human who reads both the ADR and the code, not a diff (ADR-0166 Decision).
     * Returns `null` when no PR was opened. */
    suspend fun openProposalPr(finding: DocsTruthFinding, fixDiff: String): String?

    /** The primary proposal path: an ADR-status-vs-code drift needing human triage. Returns `null`
     * when no ticket was opened. */
    suspend fun openTicket(finding: DocsTruthFinding, diagnosis: String): String?
}

interface FindingRepository {
    suspend fun save(finding: DocsTruthFinding): DocsTruthFinding
    suspend fun findActive(): List<DocsTruthFinding>
    suspend fun findById(id: String): DocsTruthFinding?
    suspend fun update(finding: DocsTruthFinding): DocsTruthFinding
}
