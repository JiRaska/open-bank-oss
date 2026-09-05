// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRolePresetRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationRolePreset
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class DelegationRolePresetNotFound(id: UUID) : RuntimeException("Delegation role preset not found: $id")

@ApplicationScoped
class DelegationRolePresetService(private val repository: DelegationRolePresetRepository, private val clock: Clock) {
    @Inject
    constructor(repository: DelegationRolePresetRepository) : this(repository, Clock.systemUTC())

    suspend fun list(): List<DelegationRolePreset> = repository.list()

    suspend fun create(
        name: String,
        description: String,
        resourceType: DelegationResourceType,
        capabilities: Set<DelegationCapability>,
    ): DelegationRolePreset {
        val now = OffsetDateTime.now(clock)
        return repository.save(
            DelegationRolePreset(
                name = name.trim(),
                description = description.trim(),
                resourceType = resourceType,
                capabilities = capabilities,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun update(
        id: UUID,
        name: String,
        description: String,
        resourceType: DelegationResourceType,
        capabilities: Set<DelegationCapability>,
    ): DelegationRolePreset {
        val existing = repository.findById(id) ?: throw DelegationRolePresetNotFound(id)
        return repository.save(
            existing.copy(
                name = name.trim(),
                description = description.trim(),
                resourceType = resourceType,
                capabilities = capabilities,
                updatedAt = OffsetDateTime.now(clock),
            ),
        )
    }

    suspend fun delete(id: UUID) {
        if (!repository.delete(id)) throw DelegationRolePresetNotFound(id)
    }
}
