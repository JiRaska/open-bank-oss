// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

/**
 * ADR-0031 D9 phase 3: the outcome of the policy pre-flight gate in front of flaky-test-hunter's
 * one GitHub write path. [Denied] MUST veto the entire PR-opening operation — no branch creation,
 * no commit, no PR — and the workflow records the disposition as policy-blocked in the audit
 * payload; [Allowed] carries the branch name the writer would create so the audit event can name
 * it without re-deriving it. Phase 4 note: the decision type stays service-specific (it names a
 * PR branch); the reusable gate itself lives in openbank-agent-policy as PolicyGate.
 */
sealed interface PrPathDecision {
    data class Allowed(val prBranch: String) : PrPathDecision

    data class Denied(val reason: String) : PrPathDecision
}
