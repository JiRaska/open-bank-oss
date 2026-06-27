// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.infrastructure.persistence.entity.DispatchResumeProposalEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class DispatchResumeProposalRepository : PanacheRepository<DispatchResumeProposalEntity>
