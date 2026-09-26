// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.application

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.domain.ContextNeighborhood
import java.time.Instant

interface ContextGraphPort {
    suspend fun neighborhood(
        namespace: ContextNamespace,
        root: String,
        asOf: Instant,
        maxNodes: Int,
        maxEdges: Int,
    ): ContextNeighborhood?
}

interface CaseAssignmentPort {
    suspend fun isAssigned(principalId: String, caseId: String, purpose: String, at: Instant): Boolean
}

interface ContextReadAuditPort {
    suspend fun record(entry: ContextReadAudit)
}

data class ContextReadAudit(
    val principalId: String,
    val caseId: String,
    val purpose: String,
    val action: String,
    val rootRef: String,
    val decision: String,
    val policyVersion: String?,
    val reasonCode: String,
    val occurredAt: Instant,
)

class ContextAccessDenied : RuntimeException()
class ContextAuthorizationUnavailable : RuntimeException()
