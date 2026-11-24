// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.scheduler

import com.openbank.sdd.application.port.`in`.ExpireIdleMandateUseCase
import com.openbank.sdd.domain.model.SddMandate
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

/**
 * One transaction per mandate for the idle-expiry sweep. The scheduler has no REST
 * `@WithTransaction` boundary to inherit, so this bean supplies it: the `EXPIRED` row and its
 * `sdd.mandate.expired.v1` outbox row commit together, and a failure rolls back only that mandate.
 * A separate bean because a CDI interceptor does not apply to a self-invocation in the scheduler.
 */
@ApplicationScoped
class MandateExpiryWriter(private val useCase: ExpireIdleMandateUseCase) {
    @WithTransaction
    fun expireIfIdle(mandate: SddMandate, today: LocalDate): Uni<Boolean> = useCase.expireIfIdle(mandate, today)
}
