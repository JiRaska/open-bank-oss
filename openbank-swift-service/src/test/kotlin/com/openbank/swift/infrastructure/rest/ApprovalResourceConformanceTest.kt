// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.web.DecideApprovalRequest
import com.openbank.libs.testing.approval.ApprovalEndpointConformance
import io.quarkus.security.identity.SecurityIdentity

/** This service's checker endpoint against the shared four-eyes conformance suite (libs-testing). */
class ApprovalResourceConformanceTest : ApprovalEndpointConformance() {
    override val resourceClass = ApprovalResource::class.java
    override val listPendingSecurity =
        EndpointSecurity(setOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"), "swift.approval.read", "")
    override val decideSecurity =
        EndpointSecurity(setOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"), "swift.approval.decide", "#id")

    override fun bind(store: ApprovalStore, identity: SecurityIdentity): Endpoint {
        val resource = ApprovalResource(store).also { it.identity = identity }
        return object : Endpoint {
            override suspend fun listPending(limit: Int) = resource.listPending(limit)

            override suspend fun decide(id: String, approve: Boolean?) =
                resource.decide(id, approve?.let { DecideApprovalRequest(approve = it) })
        }
    }
}
