// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.persistence.repository

import com.openbank.billing.application.port.out.AccountRevisionPinPort
import com.openbank.billing.infrastructure.persistence.entity.BillingAccountPinEntity
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.util.UUID

/**
 * Account -> pinned catalog revision (ADR-0341 D1). The insert is `ON CONFLICT DO NOTHING`, so two
 * pods assessing the same account at once cannot disagree: the first writer's pin stands and both read
 * it back. A pin is never updated here.
 */
@ApplicationScoped
class AccountRevisionPinRepositoryImpl(private val sf: Mutiny.SessionFactory, private val clock: Clock) :
    AccountRevisionPinPort {

    override suspend fun find(accountId: String): String? = sf.withSession { s ->
        s.find(BillingAccountPinEntity::class.java, accountId)
    }.awaitSuspending()?.productRevisionId?.toString()

    override suspend fun pinIfAbsent(accountId: String, revisionId: String): String {
        sf.withTransaction { s ->
            s.createNativeQuery<Int>(
                "INSERT INTO billing_account_pin (account_id, product_revision_id, pinned_at) " +
                    "VALUES (:a, :r, :t) ON CONFLICT (account_id) DO NOTHING",
            ).setParameter("a", accountId).setParameter("r", UUID.fromString(revisionId))
                .setParameter("t", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                .executeUpdate()
        }.awaitSuspending()
        return checkNotNull(find(accountId)) { "pin for account $accountId vanished after insert" }
    }
}
