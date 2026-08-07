// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application.port.out

/**
 * LLM synthesis port for case convergence (ADR-0244).
 */
interface CaseCoordinatorLlmPort {

    /**
     * Synthesizes agent contributions into a convergence judgement.
     *
     * @param caseContext the case class, disposition target, and serialized contributions
     * @return a proposal text when converged, `PENDING` plus a reason when not, or null
     *         when the model backend is unavailable
     */
    suspend fun synthesizeConvergence(caseContext: String): String?
}
