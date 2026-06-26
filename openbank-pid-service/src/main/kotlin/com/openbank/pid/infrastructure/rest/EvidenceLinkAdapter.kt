// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.pid.application.port.out.EvidenceLinkPort
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * No-op stub for [EvidenceLinkPort].
 *
 * Records the evidence link call to the structured log so that evidence traceability events are
 * visible in audit trails. A full outbound HTTP/Kafka implementation can replace this stub once
 * the downstream evidence-store endpoint is available.
 */
@ApplicationScoped
class EvidenceLinkAdapter : EvidenceLinkPort {

    @Suppress("LongParameterList")
    override suspend fun recordLink(partyId: UUID, caseId: String, evidenceRef: String, actor: String) {
        Log.infof(
            "evidence-link: partyId=%s caseId=%s evidenceRef=%s actor=%s",
            partyId,
            caseId,
            evidenceRef,
            actor,
        )
    }
}
