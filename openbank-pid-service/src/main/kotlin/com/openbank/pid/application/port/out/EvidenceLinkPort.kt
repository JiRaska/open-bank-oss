// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.out

import java.util.UUID

/**
 * Notifies a downstream evidence store that an evidence document has been linked to a PID case.
 *
 * The call is fire-and-confirm: the port implementation should persist or relay the link action
 * so that evidence traceability records survive independent of the PID service's own lifecycle.
 */
interface EvidenceLinkPort {
    /**
     * Records the evidence link action for [partyId] referencing [evidenceRef].
     *
     * @param partyId     the party whose PID case the evidence is being linked to
     * @param caseId      the ID of the active PID case
     * @param evidenceRef the opaque reference (URI or identifier) to the linked evidence document
     * @param actor       the identity of the operator or system that performed the link
     */
    suspend fun recordLink(partyId: UUID, caseId: String, evidenceRef: String, actor: String)
}
