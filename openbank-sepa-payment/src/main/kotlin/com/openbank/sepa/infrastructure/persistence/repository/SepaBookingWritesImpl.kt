// SPDX-License-Identifier: Apache-2.0
package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.application.port.out.SepaBookingWrites
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.infrastructure.persistence.entity.SepaPaymentEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SepaBookingWritesImpl(private val outbox: SepaPaymentOutboxRepositoryImpl) :
    SepaBookingWrites,
    PanacheRepository<SepaPaymentEntity> {
    override suspend fun acceptForBooking(payment: SepaPayment, outbox: SepaPaymentOutboxMessage): SepaPayment =
        updateWithMessages(payment, listOf(outbox), this.outbox, acceptedBooking = true)
}
