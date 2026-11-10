// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import com.openbank.libs.persistence.outbox.PanacheOutboxEntityV2
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Clock

@Entity
@Table(name = "pension_fund_accounting_outbox")
class PensionAccountingOutboxEntity : PanacheOutboxEntityV2()

@ApplicationScoped
class PensionAccountingOutbox(clock: Clock) :
    AbstractPanacheOutboxRepository<PensionAccountingOutboxEntity>(
        OutboxTableShape("pension_fund_accounting_outbox"),
        PensionAccountingOutboxEntity::class.java,
        clock,
    ),
    PanacheRepository<PensionAccountingOutboxEntity> {
    companion object {
        fun pending() = PensionAccountingOutboxEntity().also { it.status = OutboxStatus.PENDING.name }
    }
}
