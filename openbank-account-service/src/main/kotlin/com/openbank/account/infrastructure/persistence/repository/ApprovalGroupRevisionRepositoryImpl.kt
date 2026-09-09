// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.repository

import com.openbank.account.application.port.out.ApprovalGroupRevisionRepository
import com.openbank.account.application.port.out.ConflictingApprovalGroupRevisionException
import com.openbank.account.domain.model.ApprovalGroupRevision
import com.openbank.account.infrastructure.persistence.entity.ApprovalGroupRevisionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class ApprovalGroupRevisionRepositoryImpl :
    ApprovalGroupRevisionRepository,
    PanacheRepository<ApprovalGroupRevisionEntity> {
    override suspend fun store(value: ApprovalGroupRevision) {
        Panache.withTransaction {
            find(
                "groupId = ?1 and revision = ?2",
                value.groupId,
                value.revision,
            ).firstResult<ApprovalGroupRevisionEntity>().flatMap { old ->
                when {
                    old == null -> persist(ApprovalGroupRevisionEntity.from(value)).replaceWithVoid()
                    old.toDomain() == value -> io.smallrye.mutiny.Uni.createFrom().voidItem()
                    else -> io.smallrye.mutiny.Uni.createFrom().failure(
                        ConflictingApprovalGroupRevisionException(value.groupId, value.revision),
                    )
                }
            }
        }.awaitSuspending()
    }

    override suspend fun find(groupId: UUID, revision: Long): ApprovalGroupRevision? = Panache.withSession {
        find("groupId = ?1 and revision = ?2", groupId, revision).firstResult<ApprovalGroupRevisionEntity>()
    }.awaitSuspending()?.toDomain()
}
