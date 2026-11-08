// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.port.out

import com.openbank.devops.domain.model.CiGateProfileEvidence

interface CiGateProfilePort {
    /** Bounded read-only evidence collection; absent or untrusted evidence is never zero cost. */
    suspend fun collect(): CiGateProfileEvidence
}
