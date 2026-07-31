// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.infrastructure.persistence.entity.SettlementQuoteEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class SettlementQuoteRepository : PanacheRepository<SettlementQuoteEntity> {
    fun save(entity: SettlementQuoteEntity): Uni<SettlementQuoteEntity> = persistAndFlush(entity).replaceWith(entity)

    fun findLatestUnsettled(loanId: UUID): Uni<SettlementQuoteEntity?> =
        find("loanId = ?1 and settledAt is null order by createdAt desc", loanId).firstResult()

    fun markSettled(id: UUID, settledAt: OffsetDateTime): Uni<Int> =
        update("settledAt = ?1 where id = ?2", settledAt, id)
}
