// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.DelegationPortfolio
import java.util.UUID

interface DelegationPortfolioRepository {
    suspend fun save(portfolio: DelegationPortfolio): DelegationPortfolio
    suspend fun findById(id: UUID): DelegationPortfolio?
    suspend fun findByOwner(ownerPartyId: UUID): List<DelegationPortfolio>
}
