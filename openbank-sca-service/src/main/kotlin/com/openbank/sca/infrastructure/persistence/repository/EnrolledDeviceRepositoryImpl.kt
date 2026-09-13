// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.infrastructure.persistence.entity.EnrolledDeviceEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

@ApplicationScoped
class EnrolledDeviceRepositoryImpl :
    EnrolledDeviceRepository,
    PanacheRepository<EnrolledDeviceEntity> {

    @Inject
    lateinit var outboxRepo: ScaOutboxRepositoryImpl

    /**
     * ONE `Panache.withTransaction`, both legs inside it: the device row and its outbox row are
     * written by the same database transaction, so `xmin` is identical on both and a crash can
     * commit neither instead of only the first (#8679, pinned by `ScaEnrollOutboxAtomicityIT`).
     *
     * `persist`, not `merge`, is correct here even though the `@Id` is application-assigned:
     * `ScaService.enroll` returns the existing device (same party) or throws
     * `CredentialAlreadyEnrolledException` (another party) BEFORE reaching this method, so every
     * call that arrives is a fresh credential with a freshly generated id — an INSERT. The unique
     * constraint on `credential_id` is what catches the concurrent-enroll TOCTOU race, and
     * `ScaService` translates that 23505 into `CredentialAlreadyEnrolledException`.
     */
    override suspend fun saveWithOutbox(device: EnrolledDevice, outboxMessage: OutboxMessage): EnrolledDevice {
        val entity = EnrolledDeviceEntity.fromDomain(device)
        Panache.withTransaction {
            persistAndFlush(entity).chain { _ -> outboxRepo.persistInTransaction(outboxMessage) }
        }.awaitSuspending()
        return entity.toDomain()
    }

    override suspend fun findByCredentialId(credentialId: String): EnrolledDevice? =
        Panache.withSession { find("credentialId", credentialId).firstResult<EnrolledDeviceEntity>() }
            .awaitSuspending()?.toDomain()

    override suspend fun findByPartyId(partyId: UUID): List<EnrolledDevice> =
        Panache.withSession { find("partyId", partyId).list<EnrolledDeviceEntity>() }
            .awaitSuspending().map { it.toDomain() }
}
