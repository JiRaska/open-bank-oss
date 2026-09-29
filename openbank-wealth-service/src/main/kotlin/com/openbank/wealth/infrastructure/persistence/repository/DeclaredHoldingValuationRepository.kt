// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure.persistence.repository

import com.openbank.wealth.infrastructure.persistence.entity.DeclaredHoldingValuationEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Its own repository because a class can implement `PanacheRepository<T>` for exactly one entity,
 * and the fleet idiom is repository-based rather than active-record. Injected into
 * `DeclaredHoldingRepositoryImpl` so the append happens inside that repository's transaction.
 */
@ApplicationScoped
class DeclaredHoldingValuationRepository : PanacheRepository<DeclaredHoldingValuationEntity>
