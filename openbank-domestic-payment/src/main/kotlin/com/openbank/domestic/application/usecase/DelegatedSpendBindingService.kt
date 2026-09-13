// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.ApplyDelegatedSpendReservationStateUseCase
import com.openbank.domestic.application.port.`in`.FinalizeAbsentDelegatedSpendUseCase
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.port.out.ReservationProjectionApplyResult
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class DelegatedSpendBindingService(private val repository: DelegatedSpendBindingRepository) :
    ApplyDelegatedSpendReservationStateUseCase,
    FinalizeAbsentDelegatedSpendUseCase {
    override suspend fun apply(snapshot: DelegatedSpendReservationSnapshot): ReservationProjectionApplyResult =
        repository.applySnapshot(snapshot)

    override suspend fun finalizeBefore(cutoff: Instant, limit: Int): Int =
        repository.finalizeAbsentBefore(cutoff, limit)
}
