// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import com.openbank.balance.infrastructure.persistence.entity.LowBalanceAlertPreferenceEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class LowBalanceAlertRepository : PanacheRepository<LowBalanceAlertPreferenceEntity>
