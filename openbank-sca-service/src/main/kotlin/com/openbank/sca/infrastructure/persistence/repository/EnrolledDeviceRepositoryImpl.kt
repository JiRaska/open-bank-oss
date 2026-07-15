// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.repository

import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.infrastructure.persistence.entity.EnrolledDeviceEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class EnrolledDeviceRepositoryImpl :
    EnrolledDeviceRepository,
    PanacheRepository<EnrolledDeviceEntity> {

    override suspend fun save(device: EnrolledDevice): EnrolledDevice {
        val entity = EnrolledDeviceEntity.fromDomain(device)
        Panache.withTransaction { persistAndFlush(entity) }.awaitSuspending()
        return entity.toDomain()
    }

    override suspend fun findByCredentialId(credentialId: String): EnrolledDevice? =
        Panache.withSession { find("credentialId", credentialId).firstResult<EnrolledDeviceEntity>() }
            .awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID): List<EnrolledDevice> =
        Panache.withSession { find("partyId", partyId).list<EnrolledDeviceEntity>() }
            .awaitSuspending().map { it.toDomain() }
}
