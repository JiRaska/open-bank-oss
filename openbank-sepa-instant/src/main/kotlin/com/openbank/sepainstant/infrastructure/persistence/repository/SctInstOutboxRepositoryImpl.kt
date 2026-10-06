// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
import com.openbank.sepainstant.infrastructure.persistence.entity.SctInstOutboxEntity
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@ApplicationScoped
class SctInstOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<SctInstOutboxEntity>(
        OutboxTableShape("sct_inst_outbox"),
        SctInstOutboxEntity::class.java,
        clock,
    ),
    SctInstOutboxRepository
