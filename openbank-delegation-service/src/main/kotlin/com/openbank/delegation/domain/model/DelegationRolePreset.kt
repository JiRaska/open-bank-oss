// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.domain.model

import com.openbank.libs.domain.identifiers.Ids
import java.time.OffsetDateTime
import java.util.UUID

/** A reusable UI preset. Grants copy its capabilities; they never retain a mutable link to it. */
data class DelegationRolePreset(
    val id: UUID = Ids.newId(),
    val name: String,
    val description: String,
    val resourceType: DelegationResourceType,
    val capabilities: Set<DelegationCapability>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    init {
        require(name.isNotBlank()) { "role preset name is required" }
        require(name.length <= NAME_MAX_LENGTH) { "role preset name is too long" }
        require(description.length <= DESCRIPTION_MAX_LENGTH) { "role preset description is too long" }
        require(capabilities.isNotEmpty()) { "role preset must contain at least one capability" }
        val allowed = DelegationGrant.CAPABILITY_MATRIX.getValue(resourceType)
        require(
            capabilities.all {
                it in allowed
            },
        ) { "capabilities ${capabilities - allowed} are invalid for $resourceType" }
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val DESCRIPTION_MAX_LENGTH = 500
    }
}
