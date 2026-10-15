// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

/** Denial reasons shared by every [PolicyGate] implementation. Values are wire/audit-stable. */
object AgentPolicyVocabulary {
    /** The packaged charter resource could not be loaded or parsed. Deny (fail closed). */
    const val REASON_CHARTER_UNAVAILABLE = "charter_unavailable"

    /** The charter loaded but does not grant this agent the (tier, resource) pair. */
    const val REASON_CHARTER_DENIED = "charter_denied"

    /** Prefix of a policy-driven denial; the OPA reason follows after ": ". */
    const val REASON_OPA_DENIED_PREFIX = "opa_denied"

    /** OPA sidecar unreachable, timed out, or returned an unparseable response. Deny (fail closed). */
    const val REASON_PDP_UNREACHABLE = "pdp_unreachable"
}
