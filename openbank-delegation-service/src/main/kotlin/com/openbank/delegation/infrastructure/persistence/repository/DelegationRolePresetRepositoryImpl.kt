// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.DelegationRolePresetRepository
import com.openbank.delegation.domain.model.DelegationRolePreset
import com.openbank.delegation.infrastructure.persistence.entity.DelegationRolePresetEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class DelegationRolePresetRepositoryImpl :
    DelegationRolePresetRepository,
    PanacheRepository<DelegationRolePresetEntity> {
    override suspend fun list(): List<DelegationRolePreset> = Panache.withSession {
        find("order by name").list<DelegationRolePresetEntity>()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findById(id: UUID): DelegationRolePreset? =
        Panache.withSession { find("id", id).firstResult<DelegationRolePresetEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun save(preset: DelegationRolePreset): DelegationRolePreset = Panache.withTransaction {
        Panache.getSession().flatMap { it.merge(DelegationRolePresetEntity.fromDomain(preset)) }
    }.awaitSuspending().toDomain()

    override suspend fun delete(id: UUID): Boolean = Panache.withTransaction { delete("id", id) }.awaitSuspending() > 0
}
