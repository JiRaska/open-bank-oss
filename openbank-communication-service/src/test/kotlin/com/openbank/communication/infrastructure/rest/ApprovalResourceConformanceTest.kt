// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.

package com.openbank.communication.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.web.DecideApprovalRequest
import com.openbank.libs.testing.approval.ApprovalEndpointConformance
import io.quarkus.security.identity.SecurityIdentity

/** This service's checker endpoint against the shared four-eyes conformance suite (libs-testing). */
class ApprovalResourceConformanceTest : ApprovalEndpointConformance() {
    override val resourceClass = ApprovalResource::class.java
    override val listPendingSecurity =
        EndpointSecurity(setOf("ROLE_COMMS_APPROVER", "ROLE_ADMIN"), "commstyle.approval.read", "")
    override val decideSecurity =
        EndpointSecurity(setOf("ROLE_COMMS_APPROVER", "ROLE_ADMIN"), "commstyle.approval.decide", "#id")

    override fun bind(store: ApprovalStore, identity: SecurityIdentity): Endpoint {
        val resource = ApprovalResource(store).also { it.identity = identity }
        return object : Endpoint {
            override suspend fun listPending(limit: Int) = resource.listPending(limit)

            override suspend fun decide(id: String, approve: Boolean?) =
                resource.decide(id, approve?.let { DecideApprovalRequest(approve = it) })
        }
    }
}
