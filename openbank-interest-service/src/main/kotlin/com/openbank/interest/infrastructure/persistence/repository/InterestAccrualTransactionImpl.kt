// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.repository

import com.openbank.interest.application.port.out.InterestAccrualTransaction
import com.openbank.interest.domain.model.InterestAccrual
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny

@ApplicationScoped
class InterestAccrualTransactionImpl(private val sf: Mutiny.SessionFactory) : InterestAccrualTransaction {
    override fun withAccrual(block: () -> Uni<InterestAccrual>): Uni<InterestAccrual> = sf.withTransaction { block() }
}
