// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.application.port.out.SepaRefundQuery
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class SepaRefundQueryImpl : SepaRefundQuery {
    override suspend fun oldestUnresolvedReturnAt(): Instant? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select min(p.returnFirstReceivedAt) from SepaPaymentEntity p " +
                    "where p.returnFirstReceivedAt is not null and p.returnResolvedAt is null",
                Instant::class.java,
            ).singleResultOrNull
        }
    }.awaitSuspending()
}
