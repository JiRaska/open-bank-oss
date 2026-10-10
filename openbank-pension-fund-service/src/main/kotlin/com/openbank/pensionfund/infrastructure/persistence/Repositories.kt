// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class FundRepository : PanacheRepositoryBase<FundEntity, UUID>

@ApplicationScoped
class FundStrategyRepository : PanacheRepositoryBase<FundStrategyEntity, UUID>

@ApplicationScoped
class StrategyChangeRepository : PanacheRepositoryBase<StrategyChangeEntity, UUID>

@ApplicationScoped
class FundNavRepository : PanacheRepositoryBase<FundNavEntity, UUID>

@ApplicationScoped
class FundNavPositionRepository : PanacheRepositoryBase<FundNavPositionEntity, UUID>

@ApplicationScoped
class PositionClassificationCorrectionRepository : PanacheRepositoryBase<PositionClassificationCorrectionEntity, UUID>

@ApplicationScoped
class UnitOrderRepository : PanacheRepositoryBase<UnitOrderEntity, UUID>

@ApplicationScoped
class UnitHoldingRepository : PanacheRepositoryBase<UnitHoldingEntity, UUID>

@ApplicationScoped
class UnitTransactionRepository : PanacheRepositoryBase<UnitTransactionEntity, UUID>
